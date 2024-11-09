(ns restaurant
  (:require [clojure.data.json :as json]
            [java-time.api :as java-time]
            [org.corfield.ring.middleware.data-json :as data-json]
            [reitit.ring :as reitit.ring]
            [restaurant.maitre-d :as maitre-d]
            [restaurant.reservation :as reservation]
            [restaurant.reservation-book :as reservation-book]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response])
  (:import (ch.qos.logback.classic Level Logger)
           (io.opentelemetry.instrumentation.logback.appender.v1_0 OpenTelemetryAppender)
           (java.time LocalDateTime)
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

(defn handle-reservation [{:keys [maitre-d now generate-reservation-id reservation-book]}]
  (fn [{:keys [body] :as _request}]
    (let [{:keys [::reservation/error? at quantity]
           :as   bookable-reservation} (reservation/->reservation body)]
      (cond
        error?
        (response/bad-request (dissoc bookable-reservation ::reservation/error?))

        (not (maitre-d/will-accept? maitre-d (now) (reservation-book/read reservation-book at) bookable-reservation))
        (internal-server-error
          {:on          (java-time/local-date at)
           :unavailable quantity})

        :else
        (let [reservation-id (generate-reservation-id)]
          (reservation-book/book reservation-book reservation-id bookable-reservation)
          (response/created (str "/reservations/" reservation-id)))))))

(defn fetch-reservation [{:keys [reservation-book] :as _system}]
  (fn [{:keys [path-params] :as _request}]
    (->> path-params
         (:id)
         (parse-uuid)
         (reservation-book/read-reservation reservation-book)
         (response/response))))

(defn routes [system]
  [["/" {:get  #'hello-world-handler
         :name ::hello-world}]
   ["/reservations" {:post (#'handle-reservation system)
                     :name ::create-reservation}]
   ["/reservations/:id" {:get (#'fetch-reservation system)}]])

(def ^:private thread-pool
  (doto (QueuedThreadPool.)
    (QueuedThreadPool/.setVirtualThreadsExecutor (Executors/newVirtualThreadPerTaskExecutor))))

(defn- write-local-date-time [^LocalDateTime x ^Appendable out _options]
  (.append out \")
  (.append out ^String (java-time/format "yyyy-MM-dd'T'HH:mm" x))
  (.append out \"))

(extend LocalDateTime json/JSONWriter {:-write write-local-date-time})

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
  (let [server (start-server {:server                  {:port 3000}
                              :maitre-d                {:tables           [{:type :communal :seats 12}]
                                                        :seating-duration (java-time/hours 6)
                                                        :opens-at         (java-time/local-time 18)
                                                        :last-seating     (java-time/local-time 21)}
                              :now                     java-time/local-date-time
                              :generate-reservation-id random-uuid
                              :reservation-book        reservation-book/reservation-book})]
    (Runtime/.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (stop-server server))))))

(comment
  (configure-open-telemetry-logging)
  (def server (start-server {:server {:port 3000 :join? false}}))
  (stop-server server))
