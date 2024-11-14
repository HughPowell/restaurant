(ns restaurant.reservation-book
  (:refer-clojure :exclude [read])
  (:require [honey.sql :as sql]
            [java-time.api :as java-time]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]))

(defprotocol ReservationBook
  (book [this id reservation] "Book a new reservation in the reservation book")
  (read [this date] "Get the reservations for `date`")
  (read-reservation [this id] "Get the reservation with the given `id`"))

(defn- execute! [config sql]
  (with-open [connection (jdbc/get-connection config)]
    (jdbc/execute!
      connection
      (sql/format sql))))

(defn create-reservations-table [datasource]
  (execute!
    datasource
    {:create-table [:reservations :if-not-exists]
     :with-columns [[:id :int :generated-always-as-identity :primary-key]
                    [:at :timestamp-without-time-zone [:not nil]]
                    [:name [:varchar 50] [:not nil]]
                    [:email [:varchar 50] [:not nil]]
                    [:quantity :int [:not nil]]]}))

(defn add-public-id-column [datasource]
  (execute!
    datasource
    {:alter-table :reservations
     :add-column  [[:public-id :uuid :unique :if-not-exists]]}))

(defn enforce-public-id [datasource]
  (execute!
    datasource
    {:update [:reservations]
     :set    {:public-id :%gen-random-uuid}
     :where  [:is :public-id :null]})
  (execute!
    datasource
    {:alter-table  :reservations
     :alter-column [:public-id :set :not :null]}))

(defn- execute-one! [datasource sql]
  (jdbc/execute-one!
    (jdbc/get-connection datasource)
    (sql/format sql)))

(defn reservation-book [datasource]
  (reify ReservationBook
    (book [_ id {:keys [at name email quantity]}]
      (execute!
        datasource
        {:insert-into :reservations
         :columns     [:public-id :at :name :email :quantity]
         :values      [[id at name email quantity]]}))
    (read [_ date]
      (execute!
        datasource
        (let [midnight (java-time/local-date-time date 0)
              next-day (java-time/plus midnight (java-time/days 1))]
          {:select [:*]
           :from   [:reservations]
           :where  [:and
                    [:<= (java-time/instant midnight "UTC") :at]
                    [:< :at (java-time/instant next-day "UTC")]]})))
    (read-reservation [_ id]
      (execute-one!
        datasource
        {:select [:public-id :at :name :email :quantity]
         :from   [:reservations]
         :where  [:= :public-id id]}))))

(comment)
