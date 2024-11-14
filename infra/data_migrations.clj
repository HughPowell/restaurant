(ns data-migrations
  (:require [restaurant]
            [restaurant.reservation-book :as reservation-book]))

(defn migrate [datasource]
  (let [datasource (or datasource restaurant/datasource)]
    (reservation-book/create-reservations-table datasource)
    (reservation-book/add-public-id-column datasource)
    (reservation-book/enforce-public-id datasource)))
