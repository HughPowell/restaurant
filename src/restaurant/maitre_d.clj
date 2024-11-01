(ns restaurant.maitre-d)

(defn will-accept [maitre-d existing-reservations reservation]
  (let [largest-table (apply max (map :seats maitre-d))]
    (->> existing-reservations
         (cons reservation)
         (map :quantity)
         (apply +)
         (>= largest-table))))
