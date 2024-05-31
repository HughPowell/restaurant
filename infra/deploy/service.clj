(ns deploy.service
  (:require [clj-http.client :as http-client]
            [deploy.infra.docker :as docker]
            [deploy.infra.interceptors :as interceptors]
            [deploy.load-balancer :as load-balancer]
            [deploy.network :as network])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent TimeUnit)))

(defn- service-definition [network-name image-name restart-policy tag]
  {:Image            (format "%s:%s" image-name tag)
   ;; All these values must be strings to comply with the underlying Go template
   :Labels           {:traefik.enable                                              "true"
                      (keyword (format "traefik.http.routers.%s.rule" tag))        "PathPrefix(`/`)"
                      (keyword (format "traefik.http.routers.%s.entrypoints" tag)) "web"
                      :traefik.http.middlewares.test-retry.retry.attempts          "5"
                      :traefik.http.middlewares.test-retry.retry.initialinterval   "200ms"
                      :traefik.http.services.web.loadbalancer.server.port          "80"
                      :traefik.http.services.web.loadbalancer.healthCheck.path     "/health"
                      :traefik.http.services.web.loadbalancer.healthCheck.interval "10s"
                      :traefik.http.services.web.loadbalancer.healthCheck.timeout  "1s"}
   :ExposedPorts     {"80/tcp" {}}
   :NetworkingConfig {:EndpointsConfig {network-name {}}}
   :HostConfig       {:RestartPolicy {:Name (name restart-policy)}}})

(defn- healthy? [client network-name container-name localhost-port]
  (let [container-meta (docker/existing-container client container-name)
        ip-address (get-in container-meta [:NetworkSettings :Networks (keyword network-name) :IPAddress])
        ports (map (fn [[port]] (re-find #"\d+" (str port))) (get-in container-meta [:NetworkSettings :Ports]))
        load-balancer-status (try (->> (http-client/get (format "http://localhost:%d/api/http/services" localhost-port)
                                                        {:as :json})
                                       (:body)
                                       (filter (fn [service] (contains? service :loadBalancer)))
                                       (first))
                                  (catch ExceptionInfo ex
                                    (let [not-found 404]
                                      (when-not (= not-found (:status (ex-data ex)))
                                        (throw ex)))))]
    (every? (fn [port]
              (doto
                (->> (format "http://%s:%s" ip-address port)
                     (keyword)
                     (vector :serverStatus)
                     (get-in load-balancer-status)
                     (= "UP"))
                (println)))
            ports)))

(defn- ensure-healthy [client network-name container-name localhost-port]
  (loop [retries 20]
    (when (zero? retries)
      (throw (ex-info (format "Container \"%s\"failed to become healthy\n" container-name)
                      {:network-name   network-name
                       :container-name container-name})))
    (when-not (healthy? client network-name container-name localhost-port)
      (printf "Container \"%s\" is not currently healthy ... waiting before retrying ... %d retries left\n"
              container-name
              retries)
      (.sleep TimeUnit/SECONDS 3)
      (recur (dec retries))))
  (printf "Container \"%s\" is healthy\n" container-name))

(defn- production-container [client name]
  (try
    (let [production-container (docker/existing-container client name)]
      (printf "Container \"%s\" already exists\n" name)
      production-container)
    (catch ExceptionInfo ex
      (let [not-found 404]
        (when-not (= not-found (:status (ex-data ex)))
          (throw ex))
        (printf "Container \"%s\" does not exist\n" name)))))

(def deploy-service
  (letfn [(canary-service-name [service-name] (format "canary-%s" service-name))
          (production-service-name [service-name] (format "production-%s" service-name))]
    (concat
      [interceptors/bulkhead]
      (docker/clients :images :containers)
      (docker/create-image :service)
      (docker/container :service
                        (fn [request] (canary-service-name (get-in request [:service :name])))
                        (fn [{{network-name :name}               :network
                              {:keys [image restart-policy tag]} :service}]
                          (service-definition network-name image restart-policy tag)))
      [{:enter (fn [{{{:keys [containers]}     :clients
                      {service-name :name}     :service
                      {:keys [localhost-port]} :load-balancer
                      {network-name :name}     :network} :request
                     :as                                 ctx}]
                 (ensure-healthy containers network-name (canary-service-name service-name) localhost-port)
                 ctx)}
       {:enter (fn [{{{:keys [containers]} :clients
                      {:keys [name]}       :service} :request
                     :as                             ctx}]
                 (->> name
                      (production-service-name)
                      (production-container containers)
                      (assoc-in ctx [:request :service :production-info])))}
       {:enter (fn [{{{:keys [containers]}           :clients
                      {:keys [name production-info]} :service} :request
                     :as                                       ctx}]
                 (when (get-in production-info [:State :Running])
                   (docker/stop-container containers (production-service-name name) production-info))
                 ctx)
        :error (fn [{{{:keys [containers]}           :clients
                      {:keys [name production-info]} :service} :request
                     :as                                       ctx}]
                 (try
                   (when production-info
                     (docker/start-container containers (production-service-name name) {}))
                   (catch Exception _))
                 ctx)}
       {:enter (fn [{{{:keys [containers]}           :clients
                      {:keys [name production-info]} :service} :request
                     :as                                       ctx}]
                 (when production-info
                   (docker/delete-container containers (production-service-name name)))
                 ctx)}
       {:enter (fn [{{{:keys [containers]} :clients
                      {:keys [name]}       :service} :request
                     :as                             ctx}]
                 (->> (docker/rename-container containers (canary-service-name name) (production-service-name name))
                      (assoc-in ctx [:request :service :info])))}])))

(defn config [env ssh-user hostname tag]
  (let [ssh-access (format "%s@%s" ssh-user hostname)
        image (case env
                :dev "net.hughpowell/restaurant"
                :prod "ghcr.io/hughpowell/restaurant")]
    (merge
      (docker/config ssh-access)
      (network/config ssh-access)
      (load-balancer/config env ssh-user hostname)
      {:service {:name           "restaurant-service"
                 :image          image
                 :restart-policy (case env
                                   :dev "no"
                                   :prod "always")
                 :tag            tag}})))

(comment
  (require '[sieppari.core :as sieppari])
  (require '[git])

  ((fn [{:keys [env ssh-user hostname tag]}]
     (sieppari/execute
       (concat
         load-balancer/deploy-load-balancer
         deploy-service)
       (merge
         (config env ssh-user hostname tag)
         {:env env})))
   #_{:tag (git/current-tag)
      :env :dev}
   {:tag      "0b53eca"
    :ssh-user "debian"
    :hostname "restaurant.hughpowell.net"
    :env      :prod})
  )
