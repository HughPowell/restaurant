(ns restaurant.maitre-d)

(defn will-accept [maitre-d existing-reservations reservation]
  (let [capacity (apply + (map :seats maitre-d))]
    (->> existing-reservations
         (cons reservation)
         (map :quantity)
         (apply +)
         (>= capacity))))
