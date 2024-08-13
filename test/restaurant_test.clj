(ns restaurant-test
  (:require [cheshire.core :as cheshire]
            [clj-http.client :as client]
            [clojure.test :refer [deftest is use-fixtures]]
            [java-time.api :as java-time]
            [restaurant :as sut])
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

(def nil-repository (extend-protocol sut/Repository
                      nil
                      (create [_ _])))

(defn- post-reservation [port reservation]
  (client/request {:body             (cheshire/generate-string reservation)
                   :headers          {"Content-Type" "application/json"}
                   :request-method   :post
                   :scheme           :http
                   :server-name      "localhost"
                   :server-port      port
                   :throw-exceptions false
                   :uri              "/reservations"}))

(deftest ^:integration post-valid-reservation
  (let [port (ephemeral-port)
        server (sut/start-server {:server                 {:join? false :port port}
                                  :reservation-repository nil-repository})]

    (try
      (let [reservation {:date     "2023-03-10 10:00"
                         :email    "katinka@example.com"
                         :name     "Katinka Ingabogovinanana"
                         :quantity 2}
            response (post-reservation port reservation)]

        (is (client/success? response)))
      (finally (sut/stop-server server)))))

(defn- in-memory-repository []
  (let [storage (atom [])]
    (reify
      sut/Repository
      (create [_ reservation] (swap! storage conj reservation))
      IDeref
      (deref [_] @storage))))

(deftest ^:unit post-valid-reservation-when-database-is-empty
  (let [repository (in-memory-repository)
        reservation {:date     (java-time/local-date-time 2023 11 24 10 00)
                     :email    "julia@example.net"
                     :name     "Julia Domna"
                     :quantity 5}]

    (sut/create repository reservation)

    (is (some (hash-set reservation) @repository))))

(comment
  (home-returns-json)
  (post-valid-reservation)
  (post-valid-reservation-when-database-is-empty))
