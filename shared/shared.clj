(ns shared
  (:require [java-time.api :as java-time]
            [restaurant.reservation-book :as reservation-book])
  (:import (clojure.lang IDeref)))

(defn in-memory-reservation-book []
  (let [storage (atom {})]
    (reify
      reservation-book/ReservationBook
      (book [_ public-id reservation] (->> public-id
                                           (assoc reservation :id)
                                           (swap! storage assoc public-id)))
      (read [_ date] (filter
                       (fn [{:keys [at]}]
                         (let [midnight (java-time/local-date-time date 0)
                               next-day (java-time/plus midnight (java-time/days 1))]
                           (and (java-time/not-before? at midnight) (java-time/before? at next-day))))
                       (vals @storage)))
      (read-reservation [_ id]
        (get @storage id))
      IDeref
      (deref [_] @storage))))
