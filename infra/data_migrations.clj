(ns data-migrations
  (:require [restaurant]
            [restaurant.reservation-book :as reservation-book]
            [system]))

(defn migrate [datasource]
  (let [datasource' (or datasource (system/start-datasource restaurant/datasource))]
    (try
      (reservation-book/create-reservations-table datasource')
      (reservation-book/add-public-id-column datasource')
      (reservation-book/enforce-public-id datasource')
      (finally
        (when-not datasource
          (system/stop-datasource datasource))))))
