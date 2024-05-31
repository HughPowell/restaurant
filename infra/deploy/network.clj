(ns deploy.network
  (:require [contajners.core :as containers]
            [deploy.infra.docker :as docker]
            [deploy.infra.interceptors :as interceptors])
  (:import (clojure.lang ExceptionInfo)))

(defn- existing-network [client name]
  (containers/invoke client {:op                   :NetworkInspect
                             :params               {:id name}
                             :throw-exceptions     true
                             :throw-entire-message true}))

(defn- create-network [client name]
  (try
    (let [network (existing-network client name)]
      (printf "Network \"%s\" already exists\n" name)
      network)
    (catch ExceptionInfo ex
      (let [not-found 404]
        (if (= not-found (:status (ex-data ex)))
          (do
            (containers/invoke client {:op                   :NetworkCreate
                                       :data                 {:Name     name
                                                              :Driver   "bridge"
                                                              :Internal false}
                                       :throw-exceptions     true
                                       :throw-entire-message true})
            (printf "Created network \"%s\"\n" name)
            (existing-network client name))
          (throw ex))))))

(defn- tear-down-network [client name]
  (containers/invoke client {:op                   :NetworkDelete
                             :params               {:id name}
                             :throw-exceptions     true
                             :throw-entire-message true})
  (printf "Tore down network \"%s\"\n" name))

(def deploy-network
  (concat
    [interceptors/bulkhead]
    (docker/clients :networks)
    [{:enter (fn [{{{:keys [networks]} :clients
                    {:keys [name]}     :network} :request
                   :as                           ctx}]
               (assoc-in ctx [:request :network :info] (create-network networks name)))
      :error (fn [{{{:keys [networks]} :clients
                    {:keys [name]}     :network} :request
                   :as                           ctx}]
               (try
                 (tear-down-network networks name)
                 (catch Exception _))
               ctx)}]))

(defn config [ssh-access]
  (merge
    (docker/config ssh-access)
    {:network {:name "restaurant"}}))

(comment
  (require '[sieppari.core :as sieppari])

  ((fn [{:keys [env ssh-access]}]
     (sieppari/execute deploy-network
                       (merge
                         {:env env}
                         (config ssh-access))))
   #_{:env :dev}
   {:env        :prod
    :ssh-access "debian@restaurant.hughpowell.net"})
  )
