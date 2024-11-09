(ns lib.malli
  (:require [malli.core :as malli]
            [malli.experimental.time :as malli.time]
            [malli.registry]))

(defn- pos-int-schema []
  (malli/-simple-schema {:type :pos-int, :pred pos-int?, :property-pred (malli/-min-max-pred nil)}))

(defn- schemas []
  {:pos-int (pos-int-schema)})

(malli.registry/set-default-registry!
  (malli.registry/composite-registry
    (malli/default-schemas)
    (malli.time/schemas)
    (schemas)))
