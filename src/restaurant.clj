(ns restaurant
  (:require [cheshire.core :as cheshire]
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

(defn handler [_]
  (-> {:message "Hello World!"}
      (cheshire/generate-string)
      (response/response)
      (response/content-type "application/json")
      (response/charset "UTF-8")))

(defn start-server [{:keys [dev?] :as config}]
  (jetty/run-jetty (if dev? #'handler handler) config))

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
  (def server (start-server {:dev? true :port 3000 :join? false}))
  (stop-server server))
