(ns system
  (:require [clojure.core.async :as async]
            [cognitect.anomalies :as-alias anomalies]
            [hikari-cp.core :as hikari]
            [java-time.api :as java-time]
            [lib.async]
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

(defn- start-server [{:keys [server routes] :as system}]
  (let [router (reitit.ring/router (routes system))
        handler (-> router
                    (reitit.ring/ring-handler
                      (reitit.ring/create-default-handler)
                      {:middleware [#(data-json/wrap-json-body % {:keywords? true})
                                    data-json/wrap-json-response
                                    #(http/wrap-response % {:router router})]}))]
    (if (= ::none server)
      handler
      (jetty/run-jetty handler (assoc server :thread-pool thread-pool)))))

(defn- stop-server [server]
  (when (instance? Server server)
    (Server/.stop ^Server server)))

(defn start-datasource [config]
  (when-not (= config ::none)
    (hikari/make-datasource config)))

(defn stop-datasource [datasource]
  (when datasource
    (hikari/close-datasource datasource)))

(defn- start-serialisation-worker [{:keys [port timeout]}]
  (doto
    (Thread. (fn []
               (let [[response-port f args] (async/<!! port)]
                 (try
                   (if (= f ::shutdown)
                     (async/>!! response-port ::done)
                     (->> (apply f args)
                          (async/>!! response-port)))
                   (catch Exception e
                     (async/alts!! [[response-port {:restaurant/result ::anomalies/fault
                                                    :message           (ex-message e)
                                                    :data              (ex-data e)}]
                                    (async/timeout (java-time/as timeout :millis))]
                                   :priority true))))
               (recur)))
    (.setDaemon true)
    (.start)))

(defn- stop-serialisation-worker [channel]
  ((lib.async/serialise-calls channel ::shutdown)))

(defn start [{:keys [datasource reservation-channel reservation-book] :as config}]
  (start-serialisation-worker reservation-channel)
  (let [datasource (start-datasource datasource)
        system (-> config
                   (assoc :reservation-book (reservation-book datasource))
                   (assoc :datasource datasource))
        server (start-server system)]
    (assoc system :server server)))

(defn stop [{:keys [server datasource reservation-channel] :as _system}]
  (stop-server server)
  (stop-datasource datasource)
  (stop-serialisation-worker reservation-channel))

(comment)
