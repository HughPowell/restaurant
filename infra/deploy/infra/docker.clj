(ns deploy.infra.docker
  (:require [babashka.fs :as fs]
            [contajners.core :as containers]
            [deploy.infra.sh :as sh])
  (:import (clojure.lang ExceptionInfo PersistentQueue)))

(defmulti docker-client-connection (fn [env _config] env))

(defmethod docker-client-connection :dev [_env _config]
  {:uri "unix:///var/run/docker.sock"})

(defmethod docker-client-connection :prod [_env {:keys [ssh-access]}]
  (println "Setting up port forward to Docker socket")
  (sh/shell-out "which" "ssh")
  (loop [retries 10
         attempted-sockets []
         attempted-control-paths []]
    (let [localhost-socket (str (fs/path (fs/temp-dir) (str (random-uuid))))
          ssh-connection (format "%s:/var/run/docker.sock" localhost-socket)
          ssh-control-path (str (fs/path (fs/temp-dir) (str (random-uuid))))
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
            (throw (ex-info (format "Failed to port forward after %d attempts" (count attempted-sockets))
                            {:attempted-sockets       attempted-sockets
                             :attempted-control-paths attempted-control-paths}
                            result)))
          (recur (dec retries)
                 (conj attempted-sockets localhost-socket)
                 (conj attempted-control-paths ssh-control-path)))
        {:ssh-connection   ssh-connection
         :ssh-control-path ssh-control-path
         :uri              (format "unix://%s" localhost-socket)}))))

(defmulti shutdown-docker-client-connection (fn [env _config] env))

(defmethod shutdown-docker-client-connection :dev [_env _config])

(defmethod shutdown-docker-client-connection :prod [_env {:keys [ssh-connection ssh-access ssh-control-path]}]
  (println "Exiting port forwarding to the Docker socket")
  (sh/shell-out "ssh"
                "-o" "ControlMaster=auto"
                "-o" (format "ControlPath=%s" ssh-control-path)
                "-O" "exit"
                "-L" ssh-connection
                ssh-access)
  (fs/delete-if-exists ssh-control-path)
  (fs/delete-if-exists ssh-connection))

(defn- docker-client [category conn version]
  (containers/client {:engine   :docker
                      :category category
                      :conn     {:uri conn}
                      :version  version}))

(defn- remove-queued-interceptors [ctx interceptor-name]
  (update ctx :queue #(->> %
                           (remove (fn [{:keys [name]}] (= name interceptor-name)))
                           (apply conj PersistentQueue/EMPTY))))

(def ^:private connection
  {:name  ::connection
   :enter (fn [{{:keys [env clients]} :request
                :as                   ctx}]
            (-> ctx
                (remove-queued-interceptors ::connection)
                (update-in [:request :clients] merge (docker-client-connection env clients))))
   :leave (fn [{{:keys [env clients]} :request
                :as                   ctx}]
            (shutdown-docker-client-connection env clients)
            ctx)
   :error (fn [{{:keys [env clients]} :request
                :as                   ctx}]
            (try
              (shutdown-docker-client-connection env clients)
              (catch Exception _))
            ctx)})

(defn- client [type]
  (let [interceptor-name (keyword (str *ns*) (name type))]
    {:name  interceptor-name
     :enter (fn [{{{:keys [uri version]} :clients} :request
                  :as                              ctx}]
              (-> ctx
                  (remove-queued-interceptors interceptor-name)
                  (assoc-in [:request :clients type] (docker-client type uri version))))}))

(defn clients [& types]
  (into [connection] (map client types)))

(defn config [ssh-access]
  {:clients {:version    "v1.45"
             :ssh-access ssh-access}})

(defn- create-image* [client name tag]
  (letfn [(existing-image [client name]
            (containers/invoke client {:op                   :ImageInspect
                                       :params               {:name (format "%s:%s" name tag)}
                                       :throw-exceptions     true
                                       :throw-entire-message true}))]
    (try
      (existing-image client name)
      (printf "Image \"%s\" already exists\n" name)
      (catch ExceptionInfo ex
        (let [not-found 404]
          (if (= not-found (:status (ex-data ex)))
            (do
              (containers/invoke client {:op                   :ImageCreate
                                         :params               {:fromImage name}
                                         :throw-exceptions     true
                                         :throw-entire-message true})
              (printf "Created image \"%s\"\n" name))
            (throw ex)))))))

(defn create-image [component]
  [{:enter (fn [{{{:keys [images]}    :clients
                  {:keys [image tag]} component} :request
                 :as                             ctx}]
             (create-image* images image tag)
             ctx)}])


(defn existing-container [client name]
  (containers/invoke client {:op                   :ContainerInspect
                             :params               {:id name}
                             :throw-exceptions     true
                             :throw-entire-message true}))

(defn create-container [client name definition]
  (try
    (let [container (existing-container client name)]
      (printf "Container \"%s\" already exists\n" name)
      container)
    (catch ExceptionInfo ex
      (let [not-found 404]
        (if (= not-found (:status (ex-data ex)))
          (do
            (containers/invoke client {:op                   :ContainerCreate
                                       :params               {:name name}
                                       :data                 definition
                                       :throw-exceptions     true
                                       :throw-entire-message true})
            (printf "Created container \"%s\"\n" name)
            (existing-container client name))
          (throw ex))))))

(defn delete-container [client name]
  (containers/invoke client {:op                   :ContainerDelete
                             :params               {:id name}
                             :throw-exceptions     true
                             :throw-entire-message true})
  (printf "Deleted container \"%s\"\n" name))

(defn start-container [client name info]
  (if (get-in info [:State :Running])
    (do
      (printf "Container \"%s\" already started\n" name)
      info)
    (do
      (containers/invoke client {:op                   :ContainerStart
                                 :params               {:id name}
                                 :throw-exceptions     true
                                 :throw-entire-message true})
      (printf "Container \"%s\" started\n" name)
      (existing-container client name))))

(defn stop-container [client name info]
  (if (get-in info [:State :Running])
    (do
      (containers/invoke client {:op                   :ContainerStop
                                 :params               {:id name}
                                 :throw-exceptions     true
                                 :throw-entire-message true})
      (printf "Container \"%s\" stopped\n" name))
    (printf "Container \"%s\" already stopped\n" name)))

(defn container [container-key ->name ->definition]
  [{:enter (fn [{:keys                           [request]
                 {{:keys [containers]} :clients} :request
                 :as                             ctx}]
             (->> (->definition request)
                  (create-container containers (->name request))
                  (assoc-in ctx [:request container-key :info])))
    :error (fn [{:keys                           [request]
                 {{:keys [containers]} :clients} :request
                 :as                             ctx}]
             (try
               (delete-container containers (->name request))
               (catch Exception _))
             ctx)}
   {:enter (fn [{:keys                           [request]
                 {{:keys [containers]} :clients} :request
                 :as                             ctx}]
             (->> (get-in request [container-key :info])
                  (start-container containers (->name request))
                  (assoc-in ctx [:request container-key :info])))
    :error (fn [{:keys                           [request]
                 {{:keys [containers]} :clients} :request
                 :as                             ctx}]
             (try
               (->> (get-in request [container-key :info])
                    (stop-container containers (->name request)))
               (catch Exception _))
             ctx)}])

(defn rename-container [client from to]
  (containers/invoke client {:op                   :ContainerRename
                             :params               {:id   from
                                                    :name to}
                             :throw-exceptions     true
                             :throw-entire-message true})
  (printf "Renamed the container from \"%s\" to \"%s\"\n" from to)
  (existing-container client to))

(comment
  )
