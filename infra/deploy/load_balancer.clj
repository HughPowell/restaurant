(ns deploy.load-balancer
  (:require [babashka.fs :as fs]
            [clj-ssh.ssh :as ssh]
            [clj-yaml.core :as yaml]
            [deploy.lib.docker :as docker]
            [deploy.lib.host :as host]
            [deploy.lib.interceptors :as interceptors]
            [deploy.network :as network])
  (:import (com.jcraft.jsch SftpException)))

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

(defmulti update-traefik-config (fn [env _config] env))

(defn- sftp-create-dirs [channel path]
  (->> path
       (iterate fs/parent)
       (reduce
         (fn [parents parent]
           (try
             (ssh/sftp channel {} :stat (str parent))
             (reduced parents)
             (catch SftpException _
               (conj parents parent))))
         (list))
       (map (fn [directory] (ssh/sftp channel {} :mkdir (str directory))))))

(defmethod update-traefik-config :prod [_env {:keys [ssh-session config-file config]}]
  (let [channel (ssh/sftp-channel ssh-session)]
    (ssh/with-channel-connection channel
      (sftp-create-dirs channel config-file)
      (let [local-file (fs/create-temp-file)]
        (try
          (if (= config
                 (try
                   (ssh/sftp channel {} :get (str config-file) (str local-file))
                   (yaml/parse-string (slurp (str local-file)))
                   (catch SftpException _)))
            (println "Load balancer config hasn't changed")
            (do
              (println "Creating new load balancer config")
              (->> (yaml/generate-string config {:dumper-options {:flow-style :block}})
                   (spit (str config-file)))
              (ssh/sftp channel {} :put (str local-file))))
          (finally
            (fs/delete-if-exists local-file)))))))

(defmethod update-traefik-config :dev [_env {:keys [config-file config]}]
  (if (= config
         (and (fs/exists? config-file)
              (yaml/parse-string (slurp (str config-file)))))
    (println "Load balancer config hasn't changed")
    (do
      (println "Creating new load balancer config")
      (->> (yaml/generate-string config {:dumper-options {:flow-style :block}})
           (spit (str config-file))))))

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
               (update-traefik-config env {:ssh-session ssh-session
                                           :config-file config-file
                                           :config      (load-balancer-config name)})
               ctx)}]
    (docker/container :load-balancer
                      (fn [request] (get-in request [:load-balancer :name]))
                      (fn [{{:keys [config-path restart-policy]} :load-balancer
                            {network-name :name}                 :network}]
                        (load-balancer-definition (str config-path) network-name restart-policy)))
    (host/forward-port [:load-balancer :api-port] [:load-balancer :localhost-port])))

(defn config [env ssh-user hostname config-dir]
  (merge
    (host/config ssh-user hostname)
    docker/config
    network/config
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
