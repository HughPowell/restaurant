(ns deploy.lib.docker
  (:require [contajners.core :as containers]
            [deploy.lib.host :as host]
            [deploy.lib.interceptors :as interceptors])
  (:import (clojure.lang ExceptionInfo)))

(defn- docker-client [category conn version]
  (containers/client {:engine   :docker
                      :category category
                      :conn     {:uri conn}
                      :version  version}))



(defn- client [type]
  (let [interceptor-name (keyword (str *ns*) (name type))]
    {:name  interceptor-name
     :enter (fn [{{{:keys [uri version]} :clients} :request
                  :as                              ctx}]
              (-> ctx
                  (interceptors/remove-queued-interceptors interceptor-name)
                  (assoc-in [:request :clients type] (docker-client type uri version))))}))

(defn clients [& types]
  (into (host/forward-socket ::connection [:host :docker-socket] [:clients :uri]) (map client types)))

(def config {:clients {:version "v1.45"}})

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
