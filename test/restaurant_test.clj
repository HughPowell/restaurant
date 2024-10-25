(ns restaurant-test
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [clojure.test :refer [are deftest is use-fixtures]]
            [java-time.api :as java-time]
            [restaurant :as sut]
            [restaurant.reservation-book :as reservation-book])
  (:import (clojure.lang IDeref)))

(use-fixtures :once (fn [f] (sut/configure-open-telemetry-logging) (f)))

(defn- ephemeral-port
  "Returns a random [ephemeral port](https://en.wikipedia.org/wiki/Ephemeral_port) number"
  []
  (+ 49152 (rand-int (- 65535 49152))))

(deftest ^:characterisation home-returns-json
  (let [port (ephemeral-port)
        server (sut/start-server {:server {:join? false :port port}})]

    (try
      (let [response (client/request {:request-method   :get
                                      :headers          {"Accept" "application/json"}
                                      :scheme           :http
                                      :server-name      "localhost"
                                      :server-port      port
                                      :as               :auto
                                      :throw-exceptions false})]

        (is (client/success? response))
        (is (= :application/json (:content-type response))))
      (finally (sut/stop-server server)))))

(def nil-reservation-book (extend-protocol reservation-book/ReservationBook
                            nil
                            (book [_ _])))

(defn- post-reservation [port reservation]
  (client/request {:body             (cheshire/generate-string reservation)
                   :headers          {"Content-Type" "application/json"}
                   :request-method   :post
                   :scheme           :http
                   :server-name      "localhost"
                   :server-port      port
                   :throw-exceptions false
                   :uri              "/reservations"}))

(defn- reservation [at email name quantity]
  {:at       at
   :email    email
   :name     name
   :quantity quantity})

(deftest ^:integration post-valid-reservation
  (let [port (ephemeral-port)
        server (sut/start-server {:server           {:join? false :port port}
                                  :reservation-book nil-reservation-book})]

    (try
      (let [response (->> (reservation "2023-03-10T10:00" "katinka@example.com" "Katinka Ingabogovinanana" 2)
                          (post-reservation port))]

        (is (client/success? response)))
      (finally (sut/stop-server server)))))

(defn- in-memory-reservation-book []
  (let [storage (atom [])]
    (reify
      reservation-book/ReservationBook
      (book [_ reservation] (swap! storage conj reservation))
      IDeref
      (deref [_] @storage))))

(deftest ^:unit post-valid-reservation-when-database-is-empty
  (let [reservation-book (in-memory-reservation-book)]

    (are [at email name quantity]
      (some #{(reservation (java-time/local-date-time at) email (str name) quantity)}
            @(do
               ((sut/handle-reservation reservation-book) {:body (reservation at email name quantity)})
               reservation-book))

      "2023-11-24T10:00" "julia@example.net" "Julia Domna" 5
      "2024-02-13T18:15" "x@example.com" "Xenia Ng" 9
      "2023-08-23t16:55" "kite@example.edu" nil 2)))

(deftest ^:integration post-invalid-reservation
  (are [at email name quantity]
    (client/client-error?
      (let [port (ephemeral-port)
            server (sut/start-server {:server           {:join? false :port port}
                                      :reservation-book nil-reservation-book})]

        (try
          (post-reservation port {:at at :email email :name name :quantity quantity})
          (finally (sut/stop-server server)))))

    nil "j@example.net" "Jay Xerxes" 1
    "not a date" "w@example.edu" "Wk Hd" 8
    "2023-11-30T20:01" nil "Thora" 19
    "2022-01-02T12:10" "3@example.com" "3 Beard" 0
    "2045-12-31T11:45" "git@example.com" "Gil Tan" -1))

(comment
  (home-returns-json)
  (post-valid-reservation)
  (post-valid-reservation-when-database-is-empty)
  (post-invalid-reservation))
