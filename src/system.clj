(ns system
  (:require [hikari-cp.core :as hikari]
            [lib.http :as http]
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

(defn- start-server [config router]
  (-> router
      (reitit.ring/ring-handler
        (reitit.ring/create-default-handler)
        {:middleware [#(data-json/wrap-json-body % {:keywords? true})
                      data-json/wrap-json-response
                      #(http/wrap-response % {:router router})]})
      (jetty/run-jetty (assoc config :thread-pool thread-pool))))

(defn- stop-server [server]
  (Server/.stop ^Server server))

(defn start-datasource [config]
  (when-not (= config :system/none)
    (hikari/make-datasource config)))

(defn stop-datasource [datasource]
  (when datasource
    (hikari/close-datasource datasource)))

(defn start [{:keys [server routes datasource reservation-book] :as config}]
  (let [datasource (start-datasource datasource)
        system (assoc config :reservation-book (reservation-book datasource))
        server (start-server server (reitit.ring/router (routes system)))]
    (assoc system :server server :datasource datasource)))

(defn stop [{:keys [server datasource] :as _system}]
  (stop-server server)
  (stop-datasource datasource))

(comment)
