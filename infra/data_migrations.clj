(ns data-migrations
  (:require [restaurant]))

(defn migrate [_]
  (restaurant/execute!
    restaurant/reservation-book-config
    restaurant/create-reservations-table))
