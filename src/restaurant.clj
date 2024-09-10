(ns restaurant
  (:require [java-time.api :as java-time]
            [org.corfield.ring.middleware.data-json :as data-json]
            [reitit.ring :as reitit.ring]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response])
  (:import (ch.qos.logback.classic Level Logger)
           (io.opentelemetry.instrumentation.logback.appender.v1_0 OpenTelemetryAppender)
           (org.eclipse.jetty.server Server)
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

(defprotocol Repository
  (create [this reservation] "Create a new reservation in the repository"))

(defn hello-world-handler [_]
  (response/response {:message "Hello World!"}))

(defn create-reservation [reservation-repository]
  (fn [_]
    (let [reservation {:date     (java-time/local-date-time 2023 11 24 10 00)
                       :email    "julia@example.net"
                       :name     "Julia Domna"
                       :quantity 5}]
      (create reservation-repository reservation))
    (response/response "")))

(defn routes [reservation-repository]
  [["/" {:get  #'hello-world-handler
         :name ::hello-world}]
   ["/reservations" {:post (#'create-reservation reservation-repository)
                     :name ::create-reservation}]])

(defn start-server [{:keys [server reservation-repository]}]
  (-> (routes reservation-repository)
      (reitit.ring/router)
      (reitit.ring/ring-handler
        (reitit.ring/create-default-handler)
        {:middleware [data-json/wrap-json-response]})
      (jetty/run-jetty server)))

(defn stop-server [server]
  (Server/.stop ^Server server))

(defn -main [& _args]
  (configure-open-telemetry-logging)
  (let [server (start-server {:server {:port 3000}})]
    (Runtime/.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (stop-server server))))))

(comment
  (configure-open-telemetry-logging)
  (def server (start-server {:port 3000 :join? false}))
  (stop-server server))
