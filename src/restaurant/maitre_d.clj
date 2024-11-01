(ns restaurant.maitre-d)

(defn will-accept [maitre-d existing-reservations reservation]
  (->> existing-reservations
       (cons reservation)
       (map :quantity)
       (apply +)
       (>= (:seats (first maitre-d)))))
