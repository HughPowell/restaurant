(ns deploy
  (:require [babashka.fs :as fs]
            [clj-http.client :as http-client]
            [clj-yaml.core :as yaml]
            [contajners.core :as containers]
            [sieppari.core :as sieppari])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent TimeUnit)))

;; Generic docker utils

(def ^:private bulkhead {:error (fn [ctx] (clojure.pprint/pprint (:error ctx)) (dissoc ctx :error))})

(defn- docker-client [category]
  (containers/client {:engine   :docker
                      :category category
                      :conn     {:uri "unix:///var/run/docker.sock"}
                      :version  "v1.44"}))

;; Networks

(def ^:private network-client (docker-client :networks))

(defn- existing-network [name]
  (containers/invoke network-client {:op                   :NetworkInspect
                                     :params               {:id name}
                                     :throw-exceptions     true
                                     :throw-entire-message true}))

(defn- create-network [name]
  (try
    (let [network (existing-network name)]
      (printf "Network \"%s\" already exists\n" name)
      network)
    (catch ExceptionInfo ex
      (let [not-found 404]
        (if (= not-found (:status (ex-data ex)))
          (do
            (containers/invoke network-client {:op                   :NetworkCreate
                                               :data                 {:Name     name
                                                                      :Driver   "bridge"
                                                                      :Internal false}
                                               :throw-exceptions     true
                                               :throw-entire-message true})
            (printf "Created network \"%s\"\n" name)
            (existing-network name))
          (throw ex))))))

(defn- tear-down-network [name]
  (containers/invoke network-client {:op :NetworkDelete
                                     :params {:id name}
                                     :throw-exceptions true
                                     :throw-entire-message true})
  (printf "Tore down network \"%s\"\n" name))

(def ^:private deploy-network
  [bulkhead
   {:enter (fn [{{{:keys [name]} :network} :request :as ctx}]
             (assoc-in ctx [:request :network :info] (create-network name)))
    :error (fn [{{{:keys [name]} :network} :request :as ctx}]
             (tear-down-network name)
             ctx)}])

;; Containers

(def ^:private container-client (docker-client :containers))

(defn- existing-container [name]
  (containers/invoke container-client {:op                   :ContainerInspect
                                       :params               {:id name}
                                       :throw-exceptions     true
                                       :throw-entire-message true}))

(defn- create-container [name definition]
  (try
    (let [container (existing-container name)]
      (printf "Container \"%s\" already exists\n" name)
      container)
    (catch ExceptionInfo ex
      (let [not-found 404]
        (if (= not-found (:status (ex-data ex)))
          (do
            (containers/invoke container-client {:op                   :ContainerCreate
                                                 :params               {:name name}
                                                 :data                 definition
                                                 :throw-exceptions     true
                                                 :throw-entire-message true})
            (printf "Created container \"%s\"\n" name)
            (existing-container name))
          (throw ex))))))

(defn- delete-container [name]
  (containers/invoke container-client {:op :ContainerDelete
                                       :params {:id name}
                                       :throw-exceptions true
                                       :throw-entire-message true})
  (printf "Deleted container \"%s\"\n" name))

(defn- start-container [name info]
  (if (get-in info [:State :Running])
    (do
      (printf "Container \"%s\" already started\n" name)
      info)
    (do
      (containers/invoke container-client {:op                   :ContainerStart
                                           :params               {:id name}
                                           :throw-exceptions     true
                                           :throw-entire-message true})
      (printf "Container \"%s\" started\n" name)
      (existing-container name))))

(defn- stop-container [name info]
  (if (get-in info [:State :Running])
    (do
      (containers/invoke container-client {:op                   :ContainerStop
                                           :params               {:id name}
                                           :throw-exceptions     true
                                           :throw-entire-message true})
      (printf "Container \"%s\" stopped\n" name))
    (printf "Container \"%s\" already stopped\n" name)))

(defn- rename-container [from to]
  (containers/invoke container-client {:op :ContainerRename
                                       :params {:id from
                                                :name to}
                                       :throw-exceptions true
                                       :throw-entire-message true})
  (printf "Renamed the container from \"%s\" to \"%s\"\n" from to)
  (existing-container to))

(defn- get-container-if-exists [name]
  (try
    (let [production-container (existing-container name)]
      (printf "Container \"%s\" already exists\n" name)
      production-container)
    (catch ExceptionInfo ex
      (let [not-found 404]
        (when-not (= not-found (:status (ex-data ex)))
          (throw ex))
        (printf "Container \"%s\" does not exist\n" name)))))

;; Load Balancer

(defn load-balancer-config [network-name]
  {:log {:level "INFO"}
   :accessLog {}
   :api {:dashboard true
         :insecure true}
   :entryPoints {:web {:address ":80"}}
   :providers {:docker {:endpoint "unix:///var/run/docker.sock"
                        :exposedByDefault false
                        :watch true
                        :network network-name}}})

(defn- load-balancer-definition [config-path network-name]
  {:Image        "traefik:v2.11"
   :Labels       {:traefik.http.routers.api.rule    "Host(`localhost`)"
                  :traefik.http.routers.api.service "api@internal"}
   :ExposedPorts {"80/tcp"   {}
                  "8080/tcp" {}}
   :HostConfig   {:Mounts       [{:Type     "bind"
                                  :Source   "/var/run/docker.sock"
                                  :Target   "/var/run/docker.sock"
                                  :ReadOnly true}
                                 {:Type   "bind"
                                  :Source config-path
                                  :Target "/traefik.yml"}]
                  :PortBindings {"80/tcp"   [{:HostPort "80"}]
                                 "8080/tcp" [{:HostPort "8080"}]}}
   :NetworkingConfig {:EndpointsConfig {network-name {}}}})

(def ^:private deploy-load-balancer
  [bulkhead
   {:enter (fn [{{{:keys [config-path config]} :load-balancer} :request :as ctx}]
             (spit config-path (yaml/generate-string config {:dumper-options {:flow-style :block}}))
             ctx)}
   {:enter (fn [{{{:keys [name definition]} :load-balancer} :request :as ctx}]
             (assoc-in ctx [:request :load-balancer :info] (create-container name definition)))
    :error (fn [{{{:keys [name]} :load-balancer} :request :as ctx}]
             (delete-container name)
             ctx)}
   {:enter (fn [{{{:keys [name info]} :load-balancer} :request :as ctx}]
             (assoc-in ctx [:request :load-balancer :info] (start-container name info)))
    :error (fn [{{{:keys [name info]} :load-balancer} :request :as ctx}]
             (stop-container name info)
             ctx)}])

;; Service

(defn- service-definition [network-name tag]
  {:Image        (format "ghcr.io/hughpowell/restaurant:%s" (str tag))
   ;; All these values must be strings to comply with the underlying Go template
   :Labels       {:traefik.enable                                              "true"
                  (keyword (format "traefik.http.routers.%s.rule" tag))        "PathPrefix(`/`)"
                  (keyword (format "traefik.http.routers.%s.entrypoints" tag)) "web"
                  :traefik.http.middlewares.test-retry.retry.attempts          "5"
                  :traefik.http.middlewares.test-retry.retry.initialinterval   "200ms"
                  :traefik.http.services.web.loadbalancer.server.port          "80"
                  :traefik.http.services.web.loadbalancer.healthCheck.path     "/health"
                  :traefik.http.services.web.loadbalancer.healthCheck.interval "10s"
                  :traefik.http.services.web.loadbalancer.healthCheck.timeout  "1s"}
   :ExposedPorts {"80/tcp" {}}
   :NetworkingConfig {:EndpointsConfig {network-name {}}}})

(defn- healthy? [network-name container-name]
  (let [container-meta (existing-container container-name)
        ip-address (get-in container-meta [:NetworkSettings :Networks (keyword network-name) :IPAddress])
        ports (map (fn [[port]] (re-find #"\d+" (str port))) (get-in container-meta [:NetworkSettings :Ports]))
        load-balancer-status (try (->> (http-client/get "http://localhost:8080/api/http/services"
                                                    {:as :json})
                                   (:body)
                                   (filter (fn [service] (contains? service :loadBalancer)))
                                   (first))
                                  (catch ExceptionInfo ex
                                    (let [not-found 404]
                                      (when-not (= not-found (:status (ex-data ex)))
                                        (throw ex)))))]
    (every? (fn [port]
              (->> (format "http://%s:%s" ip-address port)
                   (keyword)
                   (vector :serverStatus)
                   (get-in load-balancer-status)
                   (= "UP")))
            ports)))

(defn- ensure-healthy [network-name container-name]
  (loop [retries 5]
    (when (zero? retries)
      (throw (ex-info (format "Container \"%s\"failed to become healthy\n" container-name)
                      {:network-name   network-name
                       :container-name container-name})))
    (when-not (healthy? network-name container-name)
      (printf "Container \"%s\" is not currently healthy ... waiting before retrying ... %d retries left\n"
              container-name
              retries)
      (.sleep TimeUnit/SECONDS 3)
      (recur (dec retries))))
  (printf "Container \"%s\" is healthy\n" container-name))

(def ^:private deploy-service
  (letfn [(canary-service-name [service-name] (format "canary-%s" service-name))
          (production-service-name [service-name] (format "production-%s" service-name))]
    [bulkhead
     {:enter (fn [{{{:keys [name definition]} :service} :request :as ctx}]
               (assoc-in ctx [:request :service :info] (create-container (canary-service-name name) definition)))
      :error (fn [{{{:keys [name]} :service} :request :as ctx}]
               (delete-container (canary-service-name name))
               ctx)}
     {:enter (fn [{{{:keys [name info]} :service} :request :as ctx}]
               (assoc-in ctx [:request :service :info] (start-container (canary-service-name name) info)))
      :error (fn [{{{:keys [name info]} :service} :request :as ctx}]
               (stop-container (canary-service-name name) info)
               ctx)}
     {:enter (fn [{{{service-name :name} :service
                    {network-name :name} :network} :request :as ctx}]
               (ensure-healthy network-name (canary-service-name service-name))
               ctx)}
     {:enter (fn [{{{:keys [name]} :service} :request :as ctx}]
               (assoc-in ctx
                         [:request :production-service-info]
                         (get-container-if-exists (production-service-name name))))}
     {:enter (fn [{{:keys [production-service-info] {:keys [name]} :service} :request :as ctx}]
               (when (get-in production-service-info [:State :Running])
                 (stop-container (production-service-name name) production-service-info))
               ctx)
      :error (fn [{{:keys [production-service-info] {:keys [name]} :service} :request :as ctx}]
               (when production-service-info
                 (start-container (production-service-name name) {}))
               ctx)}
     {:enter (fn [{{:keys [production-service-info] {:keys [name]} :service} :request :as ctx}]
               (when production-service-info
                 (delete-container (production-service-name name)))
               ctx)}
     {:enter (fn [{{{:keys [name]} :service} :request :as ctx}]
               (assoc-in ctx
                         [:request :service :info]
                         (rename-container (canary-service-name name) (production-service-name name))))}]))

;; Entry points

(defn deploy [tag]
  (let [network-name "restaurant"
        config-path (str (fs/canonicalize "./infra/traefik.yml"))]
    (sieppari/execute (concat
                        deploy-network
                        deploy-load-balancer
                        deploy-service
                        [identity])
                      {:network       {:name network-name}
                       :load-balancer {:name "restaurant-load-balancer"
                                       :config (load-balancer-config network-name)
                                       :config-path config-path
                                       :definition (load-balancer-definition config-path network-name)}
                       :service       {:name       "restaurant-service"
                                       :definition (service-definition network-name tag)}})))

;; Dev helpers

(defn- tear-down []
  (run!
    (fn [f] (try (f) (catch Exception _)))
    [#(stop-container "canary-restaurant-service" {:State {:Running true}})
     #(delete-container "canary-restaurant-service")
     #(stop-container "production-restaurant-service" {:State {:Running true}})
     #(delete-container "production-restaurant-service")
     #(stop-container "restaurant-load-balancer" {:State {:Running true}})
     #(delete-container "restaurant-load-balancer")
     #(tear-down-network "restaurant")]))

(comment
  )
