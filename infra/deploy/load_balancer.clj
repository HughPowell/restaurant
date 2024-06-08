(ns deploy.load-balancer
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [deploy.lib.docker :as docker]
            [deploy.lib.host :as host]
            [deploy.lib.interceptors :as interceptors]))

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
    (docker/create-image :load-balancer)
    [{:enter (fn [{{:keys                 [env]
                    {:keys [ssh-session]} :host
                    {:keys [config-file]} :load-balancer
                    {:keys [name]}        :network} :request
                   :as                              ctx}]
               (host/update-file env {:ssh-session ssh-session
                                      :parse       yaml/parse-string
                                      :generate    #(yaml/generate-string % {:dumper-options {:flow-style :block}})
                                      :file        config-file
                                      :content     (load-balancer-config name)})
               ctx)}]
    (docker/container :load-balancer
                      (fn [request] (get-in request [:load-balancer :name]))
                      (fn [{{:keys [config-file restart-policy]} :load-balancer
                            {network-name :name}                 :network}]
                        (load-balancer-definition (str config-file) network-name restart-policy)))
    (host/forward-port [:load-balancer :api-port] [:load-balancer :localhost-port])))

(defn config [env host-config docker-config network-config config-dir]
  (merge
    host-config
    docker-config
    network-config
    {:load-balancer {:name           "restaurant-load-balancer"
                     :image          "traefik"
                     :tag            "v2.11"
                     :config-file    (if config-dir
                                       (do
                                         (fs/create-dirs config-dir)
                                         (fs/path config-dir "traefik.yml"))
                                       (fs/path "/opt/restaurant/load-balancer/traefik.yml"))
                     :api-port       8080
                     :restart-policy (case env
                                       :prod :always
                                       :dev :no)}}))

(comment
  )
