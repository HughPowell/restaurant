(ns restaurant
  (:require [ring.adapter.jetty :as jetty])
  (:import (org.eclipse.jetty.server Server))
  (:gen-class))

(defn start-server [config]
  (jetty/run-jetty (fn [_] {:status 200 :body "Hello World!"}) config))

(defn stop-server [server]
  (.stop ^Server server))

(defn -main [& _args]
  (let [server (start-server {:port 3000})]
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (stop-server server))))))

(comment
  (def server (start-server {:port 3000 :join? false}))
  (stop-server server))
