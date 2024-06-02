(ns deploy.lib.host
  (:require [babashka.fs :as fs]
            [deploy.lib.sh :as sh])
  (:import (clojure.lang ExceptionInfo)))

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

(defmulti port-forward* (fn [env _config] env))

(defmethod port-forward* :dev [_env {:keys [port]}] {:localhost-port port})

(defmethod port-forward* :prod [_env {:keys [hostname port ssh-access]}]
  (printf "Setting up port forwarding to the %s:%d\n" hostname port)
  (sh/shell-out "which" "ssh")
  (loop [retries 10
         attempted-ports []
         attempted-control-paths []]
    ;; Ephemeral Port - https://en.wikipedia.org/wiki/Ephemeral_port
    (let [localhost-port (let [min-port 49152
                               max-port 65535]
                           (+ min-port (rand-int (inc (- max-port min-port)))))
          ssh-control-path (str (fs/path (fs/temp-dir) (str (random-uuid))))
          ssh-connection (format "%s:%s:%s" localhost-port hostname port)
          ;; Work around not being able to recur from a `catch` expression
          result (try
                   (sh/shell-out "ssh"
                                 "-o" "ExitOnForwardFailure=yes"
                                 "-o" "ControlMaster=auto"
                                 "-o" (format "ControlPath=%s" ssh-control-path)
                                 "-N"
                                 "-f"
                                 "-L" ssh-connection
                                 ssh-access)
                   (catch ExceptionInfo ex ex))]
      (if (instance? ExceptionInfo result)
        (if (zero? retries)
          (do
            (fs/delete-if-exists ssh-control-path)
            (throw (ex-info (format "Failed to port forward after %d attempts" (count attempted-ports))
                            {:attempted-ports         attempted-ports
                             :attempted-control-paths attempted-control-paths}
                            result)))
          (recur (dec retries) (conj attempted-ports localhost-port) (conj attempted-control-paths ssh-control-path)))
        {:ssh-connection   ssh-connection
         :localhost-port   localhost-port
         :ssh-control-path ssh-control-path}))))

(defmulti close-forwarded-port (fn [env _config] env))

(defmethod close-forwarded-port :dev [_env _config])

(defmethod close-forwarded-port :prod [_env {:keys [ssh-connection ssh-access ssh-control-path]}]
  (printf "Exiting port forwarding %s\n" ssh-connection)
  (sh/shell-out "ssh"
                "-o" "ControlMaster=auto"
                "-o" (format "ControlPath=%s" ssh-control-path)
                "-O" "exit"
                "-L" ssh-connection
                ssh-access)
  (fs/delete-if-exists ssh-control-path))

(defn port-forward [component ->remote-port]
  [{:enter (fn [{:keys                                 [request]
                 {:keys                         [env]
                  {:keys [hostname ssh-access]} :host} :request
                 :as                                   ctx}]
             (update-in
               ctx
               [:request component]
               merge
               (port-forward*
                 env
                 {:port (->remote-port request) :hostname hostname :ssh-access ssh-access})))
    :leave (fn [{{:keys                                     [env]
                  {:keys [ssh-connection ssh-control-path]} component
                  {:keys [ssh-access]}                      :host}
                 :request :as ctx}]
             (close-forwarded-port env {:ssh-connection   ssh-connection
                                        :ssh-access       ssh-access
                                        :ssh-control-path ssh-control-path})
             ctx)
    :error (fn [{{:keys                                     [env]
                  {:keys [ssh-connection ssh-control-path]} component
                  {:keys [ssh-access]}                      :host}
                 :request :as ctx}]
             (try
               (close-forwarded-port env {:ssh-connection   ssh-connection
                                          :ssh-access       ssh-access
                                          :ssh-control-path ssh-control-path})
               (catch Exception _))
             ctx)}])
