(ns restaurant
  (:require [ring.adapter.jetty :as jetty])
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

(defn start-server [config]
  (jetty/run-jetty (fn [_] {:status 200 :body "Hello World!"}) config))

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
