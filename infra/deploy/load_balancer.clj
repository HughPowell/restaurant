(ns deploy.load-balancer
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [deploy.lib.docker :as docker]
            [deploy.lib.host :as host]
            [deploy.lib.interceptors :as interceptors]
            [deploy.network :as network]))

(defn load-balancer-config [network-name]
  {:log         {:level "INFO"}
   :accessLog   {}
   :api         {:dashboard true
                 :insecure  true}
   :entryPoints {:web {:address ":80"}}
   :providers   {:docker {:endpoint         "unix:///var/run/docker.sock"
                          :exposedByDefault false
                          :watch            true
                          :network          network-name}}})

(defn- write-config [env mount-point config-dir config-file host-config-dir network-name]
  (let [full-config-path (fs/path mount-point config-dir config-file)
        local-config-path (cond->> (fs/path host-config-dir config-dir config-file)
                            (= env :dev) (fs/path mount-point))
        load-balancer-config' (load-balancer-config network-name)]
    (fs/create-dirs (fs/parent full-config-path))
    (if (and (fs/exists? full-config-path)
             (= load-balancer-config'
                (yaml/parse-string (slurp (str full-config-path)))))
      (println "Load balancer config already exists")
      (do
        (println "Creating load balancer config")
        (->> (yaml/generate-string load-balancer-config' {:dumper-options {:flow-style :block}})
             (spit (str full-config-path)))))
    local-config-path))

(defn- load-balancer-definition [config-path network-name restart-policy]
  {:Image            "traefik:v2.11"
   :Labels           {:traefik.http.routers.api.rule    "Host(`localhost`)"
                      :traefik.http.routers.api.service "api@internal"}
   :ExposedPorts     {"80/tcp"   {}
                      "8080/tcp" {}}
   :HostConfig       {:Mounts        [{:Type     "bind"
                                       :Source   "/var/run/docker.sock"
                                       :Target   "/var/run/docker.sock"
                                       :ReadOnly true}
                                      {:Type   "bind"
                                       :Source config-path
                                       :Target "/traefik.yml"}]
                      :PortBindings  {"80/tcp"   [{:HostPort "80"}]
                                      "8080/tcp" [{:HostPort "8080"}]}
                      :RestartPolicy {:Name (name restart-policy)}}
   :NetworkingConfig {:EndpointsConfig {network-name {}}}})

(def deploy-load-balancer
  (concat
    [interceptors/bulkhead]
    (docker/clients :images :containers)
    host/mount-dir
    (docker/create-image :load-balancer)
    [{:enter (fn [{{:keys                                             [env]
                    {:keys [mount-point] host-config-dir :config-dir} :host
                    {:keys [config-dir config-file]}                  :load-balancer
                    {:keys [name]}                                    :network} :request
                   :as                                                          ctx}]
               (->> (write-config env mount-point config-dir config-file host-config-dir name)
                    (assoc-in ctx [:request :load-balancer :config-path])))
      :error (fn [{{{:keys [mount-point]} :host
                    {:keys [config-dir]}  :load-balancer} :request
                   :as                                    ctx}]
               (try
                 (fs/delete-tree (fs/path mount-point config-dir))
                 (catch Exception _))
               ctx)}]
    (docker/container :load-balancer
                      (fn [request] (get-in request [:load-balancer :name]))
                      (fn [{{:keys [config-path restart-policy]} :load-balancer
                            {network-name :name}                 :network}]
                        (load-balancer-definition (str config-path) network-name restart-policy)))
    (host/forward-port [:load-balancer :api-port] [:load-balancer :localhost-port])))

(defn config [env ssh-user hostname]
  (let [ssh-access (format "%s@%s" ssh-user hostname)]
    (merge
      (docker/config ssh-access)
      (network/config ssh-access)
      {:host          {:ssh-access ssh-access
                       :config-dir (case env
                                     :dev "./"
                                     :prod "/opt/restaurant")
                       :ssh-user   ssh-user
                       :hostname   hostname}
       :load-balancer {:name        "restaurant-load-balancer"
                       :image       "traefik"
                       :tag         "v2.11"
                       :config-dir  "load-balancer"
                       :config-file "traefik.yml"
                       :api-port    8080
                       :restart-policy (case env
                                         :dev :no
                                         :prod :always)}})))

(comment
  (require '[sieppari.core :as sieppari])

  *e
  ((fn [{:keys [env ssh-user hostname]}]
     (sieppari/execute deploy-load-balancer
                       (merge
                         {:env env}
                         (config env ssh-user hostname))))
   #_{:env :dev}
   {:env      :prod
    :ssh-user "debian"
    :hostname "restaurant.hughpowell.net"})
  )
