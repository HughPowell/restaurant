(ns restaurant
  (:require [clojure.data.json :as json]
            [java-time.api :as java-time]
            [reitit.core :as reitit]
            [restaurant.maitre-d :as maitre-d]
            [restaurant.reservation :as reservation]
            [restaurant.reservation-book :as reservation-book]
            [ring.util.response :as response]
            [system])
  (:import (java.time LocalDateTime))
  (:gen-class))

(defn hello-world-handler [_]
  (response/response {:message "Hello World!"}))

(defn- internal-server-error [body]
  {:status  500
   :headers {}
   :body    body})

(defn handle-reservation [{:keys [maitre-d now generate-reservation-id reservation-book]}]
  (fn [{:keys [body] ::reitit/keys [router] :as _request}]
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
          (-> router
              (reitit/match-by-name ::fetch-reservation {:id reservation-id})
              (reitit/match->path)
              (response/created)))))))

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
   ["/reservations/:id" {:get  (#'fetch-reservation system)
                         :name ::fetch-reservation}]])

(defn- write-local-date-time [^LocalDateTime x ^Appendable out _options]
  (.append out \")
  (.append out ^String (java-time/format "yyyy-MM-dd'T'HH:mm" x))
  (.append out \"))

(extend LocalDateTime json/JSONWriter {:-write write-local-date-time})

(defn -main [& _args]
  (system/configure-open-telemetry-logging)
  (let [server (system/start {:server                  {:port 3000}
                              :routes                  routes
                              :maitre-d                {:tables           [{:type :communal :seats 12}]
                                                        :seating-duration (java-time/hours 6)
                                                        :opens-at         (java-time/local-time 18)
                                                        :last-seating     (java-time/local-time 21)}
                              :now                     java-time/local-date-time
                              :generate-reservation-id random-uuid
                              :reservation-book        (reservation-book/reservation-book)})]
    (Runtime/.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (system/stop server))))))

(comment
  (def server (system/start {:server {:port 3000 :join? false}}))
  (system/stop server))
