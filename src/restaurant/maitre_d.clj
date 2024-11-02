(ns restaurant.maitre-d
  (:require [java-time.api :as java-time]))

(defn- available-tables [tables existing-reservations]
  (reduce
    (fn [free-tables {:keys [quantity] :as existing-reservations}]
      (let [[insufficient [{:keys [seats type] :as reservable} & sufficient]]
            (split-with (fn [{:keys [seats]}] (< seats quantity)) free-tables)]
        (when-not reservable (throw (ex-info "Unable to seat existing reservation"
                                             {:tables                tables
                                              :existing-reservations existing-reservations})))
        (concat insufficient
                (if (or (= seats quantity) (= type :standard))
                  []
                  [(update reservable :seats - quantity)])
                sufficient)))
    tables
    existing-reservations))

(defn will-accept? [maitre-d existing-reservations {:keys [quantity at] :as _reservation}]
  (let [reservation-day (java-time/truncate-to at :days)
        relevant-reservations (filter (fn [{:keys [at]}]
                                        (java-time/not-before? at reservation-day))
                                      existing-reservations)]
    (->> relevant-reservations
         (available-tables maitre-d)
         (map :seats)
         (seq)
         (apply max 0)
         (<= quantity))))

(comment)
