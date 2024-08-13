(ns restaurant
  (:require [org.corfield.ring.middleware.data-json :as data-json]
            [reitit.ring :as reitit.ring]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response])
  (:import (ch.qos.logback.classic Level Logger)
           (io.opentelemetry.instrumentation.logback.appender.v1_0 OpenTelemetryAppender)
           (org.eclipse.jetty.server Server)
           (org.slf4j LoggerFactory))
  (:gen-class))

(defn configure-open-telemetry-logging []
  (let [context (LoggerFactory/getILoggerFactory)
        ^Logger logger (.getLogger context Logger/ROOT_LOGGER_NAME)]
    (.detachAppender logger "console")
    (let [open-telemetry-appender (doto (OpenTelemetryAppender.)
                                    (.setContext context)
                                    (.setCaptureCodeAttributes true)
                                    (.start))]
      (doto logger
        (.setLevel Level/INFO)
        (.addAppender open-telemetry-appender)))))

(defn hello-world-handler [_]
  (response/response {:message "Hello World!"}))

(defn post [_]
  (response/response ""))

(def routes
  [["/" {:get  #'hello-world-handler
         :name ::hello-world}]
   ["/reservations" {:post #'post
                     :name ::create-reservation}]])

(defn start-server [config]
  (-> routes
      (reitit.ring/router)
      (reitit.ring/ring-handler
        (reitit.ring/create-default-handler)
        {:middleware [data-json/wrap-json-response]})
      (jetty/run-jetty config)))

(defn stop-server [server]
  (.stop ^Server server))

(defn -main [& _args]
  (configure-open-telemetry-logging)
  (let [server (start-server {:port 3000})]
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (stop-server server))))))

(comment
  (configure-open-telemetry-logging)
  (def server (start-server {:port 3000 :join? false}))
  (stop-server server))
