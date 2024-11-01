(ns maitre-d-test
  (:require [clojure.test :refer [are deftest is]]
            [java-time.api :as java-time]
            [restaurant.maitre-d :as sut]))

(deftest ^:unit accept

  (are [maitre-d reserved-seats]
    (let [existing-reservations (map (fn [quantity]
                                       {:at       (java-time/local-date-time 2022 04 01 20 15)
                                        :email    "x@example.net"
                                        :quantity quantity})
                                     reserved-seats)
          reservation {:at       (java-time/local-date-time 2022 04 01 20 15)
                       :email    "x@example.net"
                       :quantity 11}]

      (is (sut/will-accept? maitre-d existing-reservations reservation)))

    [{:type :communal :seats 12}] []
    [{:type :communal :seats 8} {:type :communal :seats 11}] []
    [{:type :communal :seats 2} {:type :communal :seats 11}] [2]))

(deftest ^:unit reject

  (are [maitre-d reserved-seats]
    (let [existing-reservations (map (fn [quantity]
                                       {:at       (java-time/local-date-time 2022 04 01 20 15)
                                        :email    "x@example.net"
                                        :quantity quantity})
                                     reserved-seats)
          reservation {:at       (java-time/local-date-time 2022 04 01 20 15)
                       :email    "x@example.net"
                       :quantity 11}]

      (is (not (sut/will-accept? maitre-d existing-reservations reservation))))

    [{:type :communal :seats 6} {:type :communal :seats 6}] []
    [{:type :standard :seats 12}] [1]))

(comment
  (accept)
  (reject))
