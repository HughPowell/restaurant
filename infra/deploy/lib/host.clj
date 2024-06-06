(ns deploy.lib.host
  (:require [babashka.fs :as fs]
            [clj-ssh.ssh :as ssh]
            [deploy.lib.interceptors :as interceptors]
            [net.modulolotus.truegrit :as truegrit])
  (:import (com.jcraft.jsch SftpException)
           (java.util.concurrent TimeUnit)))

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

(defn- ephemeral-port []
  (let [min-port 49152
        max-port 65535]
    (+ min-port (rand-int (inc (- max-port min-port))))))

(def ^{:private true :arglists '([session remote-port])} forward-port**
  (truegrit/with-retry
    (fn [session remote-port]
      ;; Ephemeral Port - https://en.wikipedia.org/wiki/Ephemeral_port
      (let [local-port (ephemeral-port)]
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

(defmethod forward-socket* :dev [_env {:keys [remote-socket]}]
  (format "unix://%s" remote-socket))

(def ^{:private true :arglists '([session remote-socket])} forward-socket**
  (truegrit/with-retry
    (fn [session remote-socket]
      (let [local-port (ephemeral-port)]
        (.setSocketForwardingL session nil local-port remote-socket nil (.toMillis TimeUnit/SECONDS 1))
        (format "http://localhost:%d" local-port)))
    {:max-attempts  5
     :wait-duration (.toMillis TimeUnit/SECONDS 1)}))

(defmethod forward-socket* :prod [_env {:keys [ssh-session remote-socket]}]
  (forward-socket** ssh-session remote-socket))

(defmulti unforward-socket (fn [env _config] env))

(defmethod unforward-socket :dev [_env _config])

(defmethod unforward-socket :prod [_env {:keys [ssh-session local-socket]}]
  (->> local-socket
       (re-find #"http://localhost:(\d+)")
       (second)
       (parse-long)
       (.delPortForwardingL ssh-session nil)))

(defn forward-socket
  ([->remote-socket ->local-socket]
   (forward-socket nil ->remote-socket ->local-socket))
  ([name ->remote-socket ->local-socket]
   [{:name  name
     :enter (fn [{:keys                         [request]
                  {:keys                 [env]
                   {:keys [ssh-session]} :host} :request
                  :as                           ctx}]
              (-> ctx
                  (interceptors/remove-queued-interceptors name)
                  (assoc-in (cons :request ->local-socket) (forward-socket*
                                                             env
                                                             {:ssh-session   ssh-session
                                                              :remote-socket (get-in request ->remote-socket)}))))
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
              (try
                (unforward-socket env {:ssh-session ssh-session :local-socket (get-in request ->local-socket)})
                (catch Exception _))
              ctx)}]))

(defn config [ssh-user hostname]
  {:host {:ssh-user ssh-user
          :hostname hostname}})

(defmulti update-file (fn [env _config] env))

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

(defn- update-file-locally [parse generate local-file content]
  (if (= content
         (and (fs/exists? local-file)
              (parse (slurp (str local-file)))))
    (do
      (printf "%s content hasn't changed\n" (str local-file))
      false)
    (do
      (printf "Updating content for %s\n" (str local-file))
      (->> (generate config {:dumper-options {:flow-style :block}})
           (spit (str local-file)))
      true)))

(defmethod update-file :prod [_env {:keys [ssh-session parse generate file content]}]
  (let [channel (ssh/sftp-channel ssh-session)]
    (ssh/with-channel-connection channel
      (sftp-create-dirs channel (fs/parent file))
      (let [local-file (fs/create-temp-file)]
        (try
          (try
            (ssh/sftp channel {} :get (str file) (str local-file))
            (printf "Copied remote %s to local %s\n" (str file) (str local-file))
            (catch SftpException _))
          (when (update-file-locally parse generate local-file content)
            (printf "Updating %s remotely\n" (str file))
            (ssh/sftp channel {} :put (str local-file)))
          (finally
            (fs/delete-if-exists local-file)))))))

(defmethod update-file :dev [_env {:keys [parse generate file content]}]
  (update-file-locally parse generate file content))

(comment
  )
