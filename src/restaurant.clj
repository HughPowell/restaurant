(ns restaurant
  (:require [org.corfield.ring.middleware.data-json :as data-json]
            [reitit.ring :as reitit.ring]
            [restaurant.reservation-book :as reservation-book]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response])
  (:import (ch.qos.logback.classic Level Logger)
           (io.opentelemetry.instrumentation.logback.appender.v1_0 OpenTelemetryAppender)
           (java.util.concurrent Executors)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.slf4j ILoggerFactory LoggerFactory))
  (:gen-class))

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

(defn hello-world-handler [_]
  (response/response {:message "Hello World!"}))

(defn create-reservation [reservation-book]
  (fn [reservation]
    (reservation-book/create reservation-book reservation)
    (response/response "")))

(defn routes [reservation-book]
  [["/" {:get  #'hello-world-handler
         :name ::hello-world}]
   ["/reservations" {:post (#'create-reservation reservation-book)
                     :name ::create-reservation}]])

(def ^:private thread-pool
  (doto (QueuedThreadPool.)
    (QueuedThreadPool/.setVirtualThreadsExecutor (Executors/newVirtualThreadPerTaskExecutor))))

(defn start-server [{:keys [server reservation-book]}]
  (-> (routes reservation-book)
      (reitit.ring/router)
      (reitit.ring/ring-handler
        (reitit.ring/create-default-handler)
        {:middleware [data-json/wrap-json-response]})
      (jetty/run-jetty (assoc server :thread-pool thread-pool))))

(defn stop-server [server]
  (Server/.stop ^Server server))

(defn -main [& _args]
  (configure-open-telemetry-logging)
  (let [server (start-server {:server           {:port 3000}
                              :reservation-book reservation-book/reservation-book})]
    (Runtime/.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (stop-server server))))))

(comment
  (configure-open-telemetry-logging)
  (def server (start-server {:server {:port 3000 :join? false}}))
  (stop-server server))
