(ns restaurant.reservation
  (:require [clojure.pprint :as pprint]
            [malli.core :as malli]
            [malli.error]
            [malli.experimental.time :as malli.time]
            [malli.experimental.time.transform :as malli.time.transform]
            [malli.registry]
            [malli.transform]
            [malli.util]))

(malli.registry/set-default-registry!
  (malli.registry/composite-registry
    (malli/default-schemas)
    (malli.time/schemas)))

(def ^:private reservation
  [:map
   [:at :time/local-date-time]])

(defn ->reservation [json]
  (try
    (malli/coerce reservation json malli.time.transform/time-transformer)
    (catch Exception _
      (let [explanation (->> json
                             (malli/explain reservation)
                             (malli.error/humanize)
                             (malli.error/with-spell-checking)
                             (pprint/pprint) (with-out-str))]
        (throw (ex-info explanation (malli.util/explain-data reservation json)))))))

(comment
  )