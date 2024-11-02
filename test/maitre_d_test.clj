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
          updates))
  ([updates time-shift]
   (merge {:at       (java-time/plus (java-time/local-date-time 2022 04 01 20 15) time-shift)
           :email    "x@example.net"
           :quantity 11}
          updates)))

(defn- ->yesterday [reservation]
  (update reservation :at java-time/minus (java-time/days 1)))

(defn- ->tomorrow [reservation]
  (update reservation :at java-time/plus (java-time/days 1)))

(defn- ->one-hour-before [reservation]
  (update reservation :at java-time/minus (java-time/hours 1)))

(defn- ->one-hour-later [reservation]
  (update reservation :at java-time/plus (java-time/hours 1)))

(deftest ^:unit accept

  (are [maitre-d existing-reservations]
    (let [proposed-reservation (reservation)]

      (is (sut/will-accept? maitre-d existing-reservations proposed-reservation)))

    {:tables           [{:type :communal :seats 12}]
     :seating-duration (java-time/hours 6)}
    []

    {:tables           [{:type :communal :seats 8} {:type :communal :seats 11}]
     :seating-duration (java-time/hours 6)}
    []

    {:tables           [{:type :communal :seats 2} {:type :communal :seats 11}]
     :seating-duration (java-time/hours 6)}
    [(reservation {:quantity 2})]

    {:tables           [{:type :communal :seats 11}]
     :seating-duration (java-time/hours 6)}
    [(->yesterday (reservation))]

    {:tables           [{:type :communal :seats 11}]
     :seating-duration (java-time/hours 6)}
    [(->tomorrow (reservation))]

    {:tables           [{:type :communal :seats 12}]
     :seating-duration (java-time/minutes (* 60 2.5))}
    [(reservation {} (java-time/minutes (* -1 60 2.5)))]

    {:tables           [{:type :communal :seats 14}]
     :seating-duration (java-time/hours 1)}
    [(reservation {:quantity 9} (java-time/hours 1))]))

(deftest ^:unit reject

  (are [maitre-d existing-reservations]
    (let [proposed-reservation (reservation)]

      (is (not (sut/will-accept? maitre-d existing-reservations proposed-reservation))))

    {:tables           [{:type :communal :seats 6} {:type :communal :seats 6}]
     :seating-duration (java-time/hours 6)}
    []

    {:tables           [{:type :standard :seats 12}]
     :seating-duration (java-time/hours 6)}
    [(reservation {:quantity 1})]

    {:tables           [{:type :communal :seats 11}]
     :seating-duration (java-time/hours 6)}
    [(->one-hour-before (reservation))]

    {:tables           [{:type :communal :seats 11}]
     :seating-duration (java-time/hours 6)}
    [(->one-hour-later (reservation))]))

(comment
  (accept)
  (reject))
