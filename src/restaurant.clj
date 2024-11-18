(ns restaurant
  (:require [cognitect.anomalies :as-alias anomalies]
            [java-time.api :as java-time]
            [lib.http :as http]
            [restaurant.maitre-d :as maitre-d]
            [restaurant.reservation :as reservation]
            [restaurant.reservation-book :as reservation-book]
            [system])
  (:gen-class))

(defn hello-world-handler [_]
  {::result ::ok
   :message "Hello World!"})

(defn handle-reservation [{:keys [maitre-d now generate-reservation-id reservation-book]}]
  (fn [{:keys [at quantity] :as reservation}]
    (cond
      (not (maitre-d/will-accept? maitre-d (now) (reservation-book/read reservation-book at) reservation))
      {::result     ::anomalies/fault
       :on          (java-time/local-date at)
       :unavailable quantity}

      :else
      (let [reservation-id (generate-reservation-id)]
        (reservation-book/book reservation-book reservation-id reservation)
        {::result  :restaurant/created
         :resource ::reservation
         :params   {:id reservation-id}}))))

(defn fetch-reservation [{:keys [reservation-book] :as _system}]
  (fn [{:keys [path-params] :as _request}]
    (assoc
      (->> path-params
           (:id)
           (parse-uuid)
           (reservation-book/read-reservation reservation-book))
      ::result
      :restaurant/ok)))

(defn routes [system]
  [["/" {:get  #'hello-world-handler
         :name ::hello-world}]
   ["/reservations" {:post       (#'handle-reservation system)
                     :middleware [#(http/wrap-parse-request-body % {:schema reservation/reservation})]
                     :name       ::reservations}]
   ["/reservations/:id" {:get  (#'fetch-reservation system)
                         :name ::reservation}]])

(def datasource {:adapter       "postgresql"
                 :database-name "restaurant"
                 :username      "restaurant_owner"
                 :password      (System/getenv "RESTAURANT_DATABASE_PASSWORD")
                 :server-name   "ep-shy-boat-a7ii6yjj.ap-southeast-2.aws.neon.tech"
                 :port-number   5432})

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
                              :datasource              datasource
                              :reservation-book        reservation-book/reservation-book})]
    (Runtime/.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (system/stop server))))))

(comment)
