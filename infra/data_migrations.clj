(ns data-migrations
  (:require [next.jdbc :as jdbc]
            [restaurant.reservation-book :as reservation-book]))

(defn migrate [config]
  (let [datasource (jdbc/get-datasource (or config {:dbtype   "postgresql"
                                                    :dbname   "restaurant"
                                                    :user     "restaurant_owner"
                                                    :password (System/getenv "RESTAURANT_DATABASE_PASSWORD")
                                                    :host     "ep-shy-boat-a7ii6yjj.ap-southeast-2.aws.neon.tech"
                                                    :port     5432}))]
    (reservation-book/create-reservations-table datasource)
    (reservation-book/add-public-id-column datasource)
    (reservation-book/enforce-public-id datasource)))
