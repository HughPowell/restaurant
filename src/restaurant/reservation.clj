(ns restaurant.reservation)

(def reservation
  [:map
   [:at :time/local-date-time]
   [:email :string]
   [:name {:default ""} [:maybe :string]]
   [:quantity :pos-int]])

(comment)
