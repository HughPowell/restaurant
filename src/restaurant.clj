(ns restaurant
  (:require [java-time.api :as java-time]
            [org.corfield.ring.middleware.data-json :as data-json]
            [reitit.ring :as reitit.ring]
            [restaurant.maitre-d :as maitre-d]
            [restaurant.reservation :as reservation]
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

(defn- internal-server-error [body]
  {:status  500
   :headers {}
   :body    body})

(defn handle-reservation [{:keys [maitre-d reservation-book]}]
  (fn [request]
    (let [{:keys [::reservation/error? at quantity]
           :as   bookable-reservation} (->> request
                                            (:body)
                                            (reservation/->reservation))]
      (cond
        error?
        (response/bad-request (dissoc bookable-reservation ::reservation/error?))

        (not (maitre-d/will-accept? maitre-d (reservation-book/read reservation-book at) bookable-reservation))
        (internal-server-error
          {:on          (java-time/local-date at)
           :unavailable quantity})

        :else
        (do
          (reservation-book/book reservation-book bookable-reservation)
          (response/response ""))))))

(defn routes [system]
  [["/" {:get  #'hello-world-handler
         :name ::hello-world}]
   ["/reservations" {:post (#'handle-reservation system)
                     :name ::create-reservation}]])

(def ^:private thread-pool
  (doto (QueuedThreadPool.)
    (QueuedThreadPool/.setVirtualThreadsExecutor (Executors/newVirtualThreadPerTaskExecutor))))

(defn start-server [{:keys [server] :as system}]
  (-> (routes system)
      (reitit.ring/router)
      (reitit.ring/ring-handler
        (reitit.ring/create-default-handler)
        {:middleware [data-json/wrap-json-response
                      #(data-json/wrap-json-body % {:keywords? true})]})
      (jetty/run-jetty (assoc server :thread-pool thread-pool))))

(defn stop-server [server]
  (Server/.stop ^Server server))

(defn -main [& _args]
  (configure-open-telemetry-logging)
  (let [server (start-server {:server           {:port 3000}
                              :maitre-d         {:tables           [{:type :communal :seats 12}]
                                                 :seating-duration (java-time/hours 6)
                                                 :opens-at         (java-time/local-time 18)}
                              :reservation-book reservation-book/reservation-book})]
    (Runtime/.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (stop-server server))))))

(comment
  (configure-open-telemetry-logging)
  (def server (start-server {:server {:port 3000 :join? false}}))
  (stop-server server))
