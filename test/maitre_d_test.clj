(ns maitre-d-test
  (:require [clojure.test :refer [deftest is]]
            [java-time.api :as java-time]
            [restaurant.maitre-d :as sut]))

(deftest ^:unit accept
  (let [maitre-d [{:seats 12}]
        reservation {:at       (java-time/local-date-time 2022 04 01 20 15)
                     :email    "x@example.net"
                     :quantity 11}]
    (is (sut/will-accept maitre-d [] reservation))))

(comment
  (accept))
