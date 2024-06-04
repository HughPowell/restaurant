(ns deploy.lib.host
  (:require [babashka.fs :as fs]
            [clj-ssh.ssh :as ssh]
            [deploy.lib.sh :as sh]
            [net.modulolotus.truegrit :as truegrit]
            [sieppari.core :as sieppari])
  (:import (java.util.concurrent TimeUnit)))

(defmulti mount-config-dir (fn [env _config] env))

(defmethod mount-config-dir :dev [_env _config]
  (println "Creating temp directory to host load balancer config")
  (fs/create-temp-dir))

(defmethod mount-config-dir :prod [_env {:keys [ssh-access config-dir]}]
  (println "Securely mounting the host filesystem")
  (sh/shell-out "which" "sshfs")
  (let [mount-point (str (fs/create-temp-dir))]
    (try
      (sh/shell-out "sshfs" (format "%s:%s" ssh-access config-dir) mount-point)
      mount-point
      (catch Exception ex
        (fs/delete-if-exists mount-point)
        (throw ex)))))

(defmulti unmount-config-dir (fn [env _config] env))

(defmethod unmount-config-dir :dev [_env mount-point]
  (fs/delete-tree mount-point))

(defmethod unmount-config-dir :prod [_env {:keys [mount-point]}]
  (println "Unmounting the host filesystem")
  (sh/shell-out "umount" mount-point)
  (fs/delete-if-exists mount-point))

(defmulti remove-config-dir (fn [env _config] env))

(defmethod remove-config-dir :dev [_env {:keys [mount-point]}]
  (fs/delete-if-exists mount-point))

(defmethod remove-config-dir :prod [_env _config])

(def mount-dir
  [{:enter (fn [{{:keys [env host]} :request
                 :as                ctx}]
             (assoc-in ctx [:request :host :mount-point] (mount-config-dir env host)))
    :leave (fn [{{:keys [env host]} :request :as ctx}]
             (unmount-config-dir env host)
             ctx)
    :error (fn [{{:keys [env host]} :request :as ctx}]
             (try
               (remove-config-dir env host)
               (unmount-config-dir env host)
               (catch Exception _))
             ctx)}])

(defmulti ssh-session* (fn [env _config] env))

(defmethod ssh-session* :dev [_env _config])

(defmethod ssh-session* :prod [_env {:keys [hostname ssh-user]}]
  (println "Connecting SSH session")
  (let [session (ssh/session (ssh/ssh-agent {}) hostname {:username ssh-user})]
    (ssh/connect session)
    session))

(defmulti disconnect-ssh-session (fn [env _config] env))

(defmethod disconnect-ssh-session :dev [_env _config])

(defmethod disconnect-ssh-session :prod [_env {:keys [ssh-session]}]
  (println "Disconnecting SSH session")
  (ssh/disconnect ssh-session))

(def ssh-session
  [{:enter (fn [{{:keys [env host]} :request
                 :as                ctx}]
             (println "Made it to ssh-session")
             (let [session (ssh-session* env host)]
               (assoc-in ctx [:request :host :ssh-session] session)))
    :leave (fn [{{:keys [env host]} :request
                 :as                ctx}]
             (disconnect-ssh-session env host)
             ctx)
    :error (fn [{{:keys [env host]} :request
                 :as                ctx}]
             (try
               (disconnect-ssh-session env host)
               (catch Exception _))
             ctx)}])

(defmulti forward-port* (fn [env _config] env))

(defmethod forward-port* :dev [_env {:keys [remote-port]}] remote-port)

(def ^:private forward-port**
  (truegrit/with-retry
    (fn [session remote-port]
      ;; Ephemeral Port - https://en.wikipedia.org/wiki/Ephemeral_port
      (let [local-port (let [min-port 49152
                             max-port 65535]
                         (+ min-port (rand-int (inc (- max-port min-port)))))]
        (ssh/forward-local-port session local-port remote-port)
        local-port))
    {:max-attempts  5
     :wait-duration (.toMillis TimeUnit/SECONDS 1)}))

(defmethod forward-port* :prod [_env {:keys [ssh-session remote-port]}]
  (forward-port** ssh-session remote-port))

(defmulti unforward-port (fn [env _config] env))

(defmethod unforward-port :dev [_env _config])

(defmethod unforward-port :prod [_env {:keys [ssh-session local-port]}]
  (ssh/unforward-local-port ssh-session local-port))

(defn forward-port [->remote-port ->local-port]
  [{:enter (fn [{:keys                         [request]
                 {:keys                 [env]
                  {:keys [ssh-session]} :host} :request
                 :as                           ctx}]
             (println "Made it to forward-port")
             (->> (forward-port* env {:ssh-session ssh-session :remote-port (get-in request ->remote-port)})
                  (assoc-in ctx (cons :request ->local-port))))
    :leave (fn [{:keys                         [request]
                 {:keys                 [env]
                  {:keys [ssh-session]} :host} :request
                 :as                           ctx}]
             (unforward-port env {:ssh-session ssh-session :local-port (get-in request ->local-port)})
             ctx)
    :error (fn [{:keys                         [request]
                 {:keys                 [env]
                  {:keys [ssh-session]} :host} :request
                 :as                           ctx}]
             (try
               (unforward-port env {:ssh-session ssh-session :local-port (get-in request ->local-port)})
               (catch Exception _))
             ctx)}])

(defmulti forward-socket* (fn [env _config] env))

(defmethod forward-socket* :dev [_env {:keys [local-socket]}]
  (format "unix://%s" local-socket))

(def ^:private forward-socket**
  (truegrit/with-retry
    (fn [session remote-socket]
      (let [local-socket (str (fs/path (fs/temp-dir) (str (random-uuid))))]
        (ssh/forward-local-port session local-socket remote-socket nil)
        (format "unix://%s" local-socket)))
    {:max-attempts  5
     :wait-duration (.toMillis TimeUnit/SECONDS 1)}))

(defmethod forward-socket* :prod [_env {:keys [ssh-session remote-socket]}]
  (forward-socket** ssh-session remote-socket))

(defmulti unforward-socket (fn [env _config] env))

(defmethod unforward-socket :dev [_env _config])

(defmethod unforward-socket :prod [_env {:keys [ssh-session local-socket]}]
  (ssh/unforward-local-port ssh-session local-socket))

(defn forward-socket [->remote-socket ->local-socket]
  [{:enter (fn [{:keys                                          [request]
                 {:keys                 [env]
                  {:keys [ssh-session]} :host} :request
                 :as                           ctx}]
             (->> (forward-socket* env {:ssh-session ssh-session :remote-socket (get-in request ->remote-socket)})
                  (assoc-in ctx ->local-socket)))
    :leave (fn [{:keys                         [request]
                 {:keys                 [env]
                  {:keys [ssh-session]} :host} :request
                 :as                           ctx}]
             (unforward-socket env {:ssh-session ssh-session :local-socket (get-in request ->local-socket)})
             ctx)
    :error (fn [{:keys                         [request]
                 {:keys                 [env]
                  {:keys [ssh-session]} :host} :request
                 :as                           ctx}]
             (unforward-socket env {:ssh-session ssh-session :local-socket (get-in request ->local-socket)})
             ctx)}])

(defn config [ssh-user hostname]
  {:host {:ssh-user ssh-user
          :hostname hostname}})

(comment
  (require '[clj-http.client :as http-client])

  (sieppari/execute
    [ssh-session
     (forward-port [:host :remote-port] [:host :local-port])
     (fn [{{:keys [local-port]} :host}]
       (println local-port)
       (http-client/get (format "http://localhost:%d" local-port)))]
    {:env  :prod
     :host {:hostname    "restaurant.hughpowell.net"
            :ssh-user    "debian"
            :remote-port 80}})
  *e
  )
