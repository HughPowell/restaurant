(ns data-migrations
  (:require [restaurant.reservation-book :as reservation-book]))

(defn migrate [_]
  (reservation-book/create-reservations-table))
