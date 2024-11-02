(ns maitre-d-test
  (:require [clojure.test :refer [are deftest is]]
            [java-time.api :as java-time]
            [restaurant.maitre-d :as sut]))

(defn- reservation
  ([] (reservation {}))
  ([updates]
   (merge {:at       (java-time/local-date-time 2022 04 01 20 15)
           :email    "x@example.net"
           :quantity 11}
          updates)))

(defn- ->yesterday [reservation]
  (update reservation :at java-time/minus (java-time/days 1)))

(deftest ^:unit accept

  (are [maitre-d existing-reservations]
    (let [proposed-reservation (reservation)]

      (is (sut/will-accept? maitre-d existing-reservations proposed-reservation)))

    [{:type :communal :seats 12}] []
    [{:type :communal :seats 8} {:type :communal :seats 11}] []
    [{:type :communal :seats 2} {:type :communal :seats 11}] [(reservation {:quantity 2})]
    [{:type :communal :seats 11}] [(->yesterday (reservation))]))

(deftest ^:unit reject

  (are [maitre-d existing-reservations]
    (let [proposed-reservation (reservation)]

      (is (not (sut/will-accept? maitre-d existing-reservations proposed-reservation))))

    [{:type :communal :seats 6} {:type :communal :seats 6}] []
    [{:type :standard :seats 12}] [(reservation {:quantity 1})]))

(comment
  (accept)
  (reject))
