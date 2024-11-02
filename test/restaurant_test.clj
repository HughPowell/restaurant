(ns restaurant-test
  (:refer-clojure :exclude [read])
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

(def ^:private maitre-d
  {:tables           [{:type :communal :seats 12}]
   :seating-duration (java-time/hours 6)
   :opens-at         (java-time/local-time 16)
   :last-seating     (java-time/local-time 21)})

(def nil-reservation-book (extend-protocol reservation-book/ReservationBook
                            nil
                            (book [_ _])
                            (read [_ _])))

(defmacro with-http-server [[port-sym port-fn] & body]
  `(let [~port-sym ~port-fn
         server# (sut/start-server {:server           {:join? false :port ~port-sym}
                                    :maitre-d         maitre-d
                                    :now              (constantly (java-time/local-date-time 2022 04 01 20 15))
                                    :reservation-book nil-reservation-book})]

     (try
       ~@body
       (finally (sut/stop-server server#)))))

(deftest ^:characterisation home-returns-json
  (with-http-server [port (ephemeral-port)]
    (let [response (client/request {:request-method   :get
                                    :headers          {"Accept" "application/json"}
                                    :scheme           :http
                                    :server-name      "localhost"
                                    :server-port      port
                                    :as               :auto
                                    :throw-exceptions false})]

      (is (client/success? response))
      (is (= :application/json (:content-type response))))))

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
  (let [res (reservation "2023-03-10T19:00" "katinka@example.com" "Katinka Ingabogovinanana" 2)

        response (with-http-server [port (ephemeral-port)]
                   (post-reservation port res))]

    (is (client/success? response))))

(deftest ^:integration post-invalid-reservation
  (are [at email name quantity]
    (let [res (reservation at email name quantity)

          response (with-http-server [port (ephemeral-port)]
                     (post-reservation port res))]

      (client/client-error? response))

    nil "j@example.net" "Jay Xerxes" 1
    "not a date" "w@example.edu" "Wk Hd" 8
    "2023-11-30T20:01" nil "Thora" 19
    "2022-01-02T12:10" "3@example.com" "3 Beard" 0
    "2045-12-31T11:45" "git@example.com" "Gil Tan" -1))

(defn- in-memory-reservation-book []
  (let [storage (atom [])]
    (reify
      reservation-book/ReservationBook
      (book [_ reservation] (swap! storage conj reservation))
      (read [_ date] (filter
                       (fn [{:keys [at]}]
                         (let [midnight (java-time/local-date-time date 0)
                               next-day (java-time/plus midnight (java-time/days 1))]
                           (and (java-time/not-before? at midnight) (java-time/before? at next-day))))
                       @storage))
      IDeref
      (deref [_] @storage))))

(defn- in-memory-system []
  {:reservation-book (in-memory-reservation-book)
   :maitre-d         maitre-d
   :now              (constantly (java-time/local-date-time 2022 01 01 18 00))})

(deftest ^:unit post-valid-reservation-when-database-is-empty
  (let [system (in-memory-system)]

    (are [at email name quantity]
      (do
        (let [request {:body (reservation at email name quantity)}]

          ((sut/handle-reservation system) request))

        (some #{(reservation (java-time/local-date-time at) email (str name) quantity)}
              @(:reservation-book system)))

      "2023-11-24T19:00" "julia@example.net" "Julia Domna" 5
      "2024-02-13T18:15" "x@example.com" "Xenia Ng" 9
      "2023-08-23t16:55" "kite@example.edu" nil 2
      "2022-03-18T17:30" "shli@example.org" "Shangri La" 5)))

(deftest ^:unit overbook-attempt
  (let [system (in-memory-system)
        handle-reservation (sut/handle-reservation system)]
    (handle-reservation {:body (reservation "2022-03-18T17:30" "mars@example.edu" "Maria Seminova" 6)})

    (let [response (handle-reservation {:body (reservation "2022-03-18T17:30" "shli@example.org" "Shangri La" 7)})]

      (is (client/server-error? response)))))

(deftest ^:unit book-table-when-free-seating-is-available
  (let [system (in-memory-system)
        handle-reservation (sut/handle-reservation system)]
    (handle-reservation {:body (reservation "2022-01-02T18:15" "net@example.net" "Ned Tucker" 2)})

    (let [response (->> (reservation "2022-01-02T18:30" "kant@example.edu" "Katrine Nohr Troleslen" 4)
                        (hash-map :body)
                        (handle-reservation))]

      (is (client/success? response)))))

(comment
  (home-returns-json)
  (post-valid-reservation)
  (post-valid-reservation-when-database-is-empty)
  (post-invalid-reservation)
  (overbook-attempt)
  (book-table-when-free-seating-is-available))
