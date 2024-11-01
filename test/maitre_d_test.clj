(ns maitre-d-test
  (:require [clojure.test :refer [are deftest is]]
            [java-time.api :as java-time]
            [restaurant.maitre-d :as sut]))

(deftest ^:unit accept

  (are [table-seats reserved-seats]
    (let [maitre-d (map #(hash-map :seats %) table-seats)
          existing-reservations (map (fn [quantity]
                                       {:at       (java-time/local-date-time 2022 04 01 20 15)
                                        :email    "x@example.net"
                                        :quantity quantity})
                                     reserved-seats)
          reservation {:at       (java-time/local-date-time 2022 04 01 20 15)
                       :email    "x@example.net"
                       :quantity 11}]

      (is (sut/will-accept? maitre-d existing-reservations reservation)))

    [12] []
    [8 11] []
    [2 11] [2]))

(deftest ^:unit reject
  (let [maitre-d [{:seats 6} {:seats 6}]
        reservation {:at       (java-time/local-date-time 2022 04 01 20 15)
                     :email    "x@example.net"
                     :quantity 11}]

    (is (not (sut/will-accept? maitre-d [] reservation)))))

(comment
  (accept))
