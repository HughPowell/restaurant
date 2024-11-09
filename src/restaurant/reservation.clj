(ns restaurant.reservation
  (:require [cognitect.anomalies :as-alias anomalies]
            [lib.malli]
            [malli.core :as malli]
            [malli.error]
            [malli.experimental.time.transform :as malli.time.transform]
            [malli.transform])
  (:import (clojure.lang ExceptionInfo)))

(def ^:private reservation
  [:map
   [:at :time/local-date-time]
   [:email :string]
   [:name {:default ""} [:maybe :string]]
   [:quantity :pos-int]])

(defn ->reservation [request-data]
  (try
    (malli/coerce reservation request-data (malli.transform/transformer
                                             malli.transform/default-value-transformer
                                             malli.time.transform/time-transformer))
    (catch ExceptionInfo e
      (-> e
          (ex-data)
          (get-in [:data :explain])
          (malli.error/humanize)
          (malli.error/with-spell-checking)
          (merge {::error ::anomalies/incorrect})))))

(comment)
