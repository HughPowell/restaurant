(ns restaurant.reservation-book
  (:refer-clojure :exclude [read])
  (:require [honey.sql :as sql]
            [java-time.api :as java-time]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]))

(defprotocol ReservationBook
  (book [this reservation] "Book a new reservation in the reservation book")
  (read [this date] "Get the reservations for `date`"))

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
         :values      [[at name email quantity]]}))
    (read [_ date]
      (execute!
        reservation-book-config
        (let [midnight (java-time/local-date-time date 0)
              next-day (java-time/plus midnight (java-time/days 1))]
          {:select [:*]
           :from   [:reservations]
           :where  [:and
                    [:<= (java-time/instant midnight "UTC") :at]
                    [:< :at (java-time/instant next-day "UTC")]]})))))

(comment)
