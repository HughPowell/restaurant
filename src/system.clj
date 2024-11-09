(ns system
  (:require [lib.http :as http]
            [lib.json]
            [org.corfield.ring.middleware.data-json :as data-json]
            [reitit.ring]
            [ring.adapter.jetty :as jetty])
  (:import (ch.qos.logback.classic Level Logger)
           (io.opentelemetry.instrumentation.logback.appender.v1_0 OpenTelemetryAppender)
           (java.util.concurrent Executors)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.slf4j ILoggerFactory LoggerFactory)))

(defn configure-open-telemetry-logging []
  (let [context (LoggerFactory/getILoggerFactory)
        ^Logger logger (ILoggerFactory/.getLogger context Logger/ROOT_LOGGER_NAME)]
    (Logger/.detachAppender logger "console")
    (let [open-telemetry-appender (doto (OpenTelemetryAppender.)
                                    (OpenTelemetryAppender/.setContext context)
                                    (OpenTelemetryAppender/.setCaptureCodeAttributes true)
                                    (.start))]
      (doto logger
        (Logger/.setLevel Level/INFO)
        (Logger/.addAppender open-telemetry-appender)))))

(def ^:private thread-pool
  (doto (QueuedThreadPool.)
    (QueuedThreadPool/.setVirtualThreadsExecutor (Executors/newVirtualThreadPerTaskExecutor))))

(defn- start-server [{:keys [router server] :as _system}]
  (-> router
      (reitit.ring/ring-handler
        (reitit.ring/create-default-handler)
        {:middleware [#(data-json/wrap-json-body % {:keywords? true})
                      data-json/wrap-json-response
                      #(http/wrap-response % {:router router})]})
      (jetty/run-jetty (assoc server :thread-pool thread-pool))))

(defn- stop-server [server]
  (Server/.stop ^Server server))

(defn start [{:keys [routes] :as configuration}]
  (let [system (assoc configuration :routes (routes configuration))
        system' (->> (:routes system)
                     (reitit.ring/router)
                     (assoc system :router))]
    (->> (start-server system')
         (assoc system' :server))))

(defn stop [{:keys [server] :as _system}]
  (stop-server server))

(comment)
