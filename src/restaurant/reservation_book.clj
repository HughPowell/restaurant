(ns restaurant.reservation-book
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]))

(defprotocol ReservationBook
  (book [this reservation] "Book a new reservation in the reservation book"))

(def ^:private reservation-book-config {:dbtype   "postgresql"
                                        :dbname   "restaurant"
                                        :user     "restaurant_owner"
                                        :password (System/getenv "RESTAURANT_DATABASE_PASSWORD")
                                        :host     "ep-shy-boat-a7ii6yjj.ap-southeast-2.aws.neon.tech"
                                        :port     5432})

(defn- execute! [config sql]
  (jdbc/execute!
    (jdbc/get-connection config)
    (sql/format sql)))

(defn create-reservations-table []
  (execute!
    reservation-book-config
    {:create-table [:reservations :if-not-exists]
     :with-columns [[:id :int :generated-always-as-identity :primary-key]
                    [:at :timestamp-without-time-zone [:not nil]]
                    [:name [:varchar 50] [:not nil]]
                    [:email [:varchar 50] [:not nil]]
                    [:quantity :int [:not nil]]]}))

(def reservation-book
  (reify ReservationBook
    (book [_ {:keys [at name email quantity]}]
      (execute!
        reservation-book-config
        {:insert-into :reservations
         :columns     [:at :name :email :quantity]
         :values      [[at name email quantity]]}))))
