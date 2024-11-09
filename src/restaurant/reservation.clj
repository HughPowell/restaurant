(ns restaurant.reservation
  (:require [malli.core :as malli]
            [malli.error]
            [malli.experimental.time :as malli.time]
            [malli.experimental.time.transform :as malli.time.transform]
            [malli.registry]
            [malli.transform]
            [malli.util])
  (:import (clojure.lang ExceptionInfo)))

(defn- pos-int-schema []
  (malli/-simple-schema {:type :pos-int, :pred pos-int?, :property-pred (malli/-min-max-pred nil)}))

(defn- schemas []
  {:pos-int (pos-int-schema)})

(malli.registry/set-default-registry!
  (malli.registry/composite-registry
    (malli/default-schemas)
    (malli.time/schemas)
    (schemas)))

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
          (merge {::error? true})))))

(comment)
