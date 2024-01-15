(ns restaurant
  (:require [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn -main [& _args] (jetty/run-jetty (fn [_] {:status 200 :body "Hello World!"}) {}))
