(ns restaurant.maitre-d
  (:require [java-time.api :as java-time]))

(defn- allocate [tables existing-reservations]
  (reduce
    (fn [free-tables {:keys [quantity] :as existing-reservations}]
      (let [[insufficient [{:keys [seats type] :as reservable} & remaining]]
            (split-with (fn [{:keys [seats]}] (< seats quantity)) free-tables)]
        (when-not reservable (throw (ex-info "Unable to seat existing reservation"
                                             {:tables                tables
                                              :existing-reservations existing-reservations})))
        (concat insufficient
                (if (or (= seats quantity) (= type :standard))
                  []
                  [(update reservable :seats - quantity)])
                remaining)))
    tables
    existing-reservations))

(defn- today's-reservations [at reservations]
  (letfn [(overlaps? [at reservation]
            (= (java-time/truncate-to at :days)
               (java-time/truncate-to (:at reservation) :days)))]
    (filter #(overlaps? at %) reservations)))

(defn will-accept? [{:keys [tables]} existing-reservations {:keys [quantity at] :as _reservation}]
  (->> existing-reservations
       (today's-reservations at)
       (allocate tables)
       (some (fn [{:keys [seats]}] (<= quantity seats)))))

(comment)
