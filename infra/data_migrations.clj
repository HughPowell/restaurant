(ns data-migrations
  (:require [restaurant]))

(defn migrate [_]
  (restaurant/execute!
    restaurant/repository-config
    restaurant/create-reservations-table))

(comment)