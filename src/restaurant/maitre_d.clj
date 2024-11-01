(ns restaurant.maitre-d)

(defn- available-tables [tables existing-reservations]
  (reduce
    (fn [free-tables {:keys [quantity] :as existing-reservations}]
      (let [[insufficient [{:keys [seats] :as reservable} & sufficient]]
            (split-with (fn [{:keys [seats]}] (< seats quantity)) free-tables)]
        (when-not reservable (throw (ex-info "Unable to seat existing reservation"
                                             {:tables                tables
                                              :existing-reservations existing-reservations})))
        (concat insufficient
                (if (= seats quantity)
                  []
                  [(update reservable :seats - quantity)])
                sufficient)))
    tables
    existing-reservations))

(defn will-accept? [maitre-d existing-reservations {:keys [quantity] :as _reservation}]
  (->> existing-reservations
       (available-tables maitre-d)
       (map :seats)
       (apply max)
       (<= quantity)))

(comment)
