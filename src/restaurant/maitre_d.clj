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

(defn- relevant-reservations [seating-duration at reservations]
  (letfn [(overlaps? [seating-duration at other]
            (let [start at
                  end (java-time/plus start seating-duration)
                  other-start (:at other)
                  other-end (java-time/plus other-start seating-duration)]
              (and (java-time/before? start other-end)
                   (java-time/before? other-start end))))]
    (filter #(overlaps? seating-duration at %) reservations)))

(defn will-accept? [{:keys [tables seating-duration opens-at last-seating] :as _maitre-d}
                    existing-reservations
                    {:keys [quantity at] :as _reservation}]
  (and
    (java-time/not-before? (java-time/local-time at) opens-at)
    (java-time/not-after? (java-time/local-time at) last-seating)
    (->> existing-reservations
         (relevant-reservations seating-duration at)
         (allocate tables)
         (some (fn [{:keys [seats]}] (<= quantity seats))))))

(comment)
