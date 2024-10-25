(ns data-migrations
  (:require [restaurant.reservation-book :as reservation-book]))

(defn migrate [_]
  (reservation-book/execute!
    reservation-book/reservation-book-config
    reservation-book/create-reservations-table))
