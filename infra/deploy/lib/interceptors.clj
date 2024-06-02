(ns deploy.lib.interceptors
  (:require [clojure.pprint :as pprint]))

(def bulkhead
  {:error (fn [ctx]
            (let [error (:error ctx)
                  ctx' (if (and error (get-in ctx [:response :error]))
                         ctx
                         (assoc-in ctx [:response :error] error))]
              (pprint/pprint error)
              (dissoc ctx' :error)))})
