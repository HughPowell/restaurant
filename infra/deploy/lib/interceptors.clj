(ns deploy.lib.interceptors
  (:require [clojure.pprint :as pprint])
  (:import (clojure.lang PersistentQueue)))

(def bulkhead
  {:error (fn [ctx]
            (let [error (:error ctx)
                  ctx' (if (and error (get-in ctx [:response :error]))
                         ctx
                         (assoc-in ctx [:response :error] error))]
              (pprint/pprint error)
              (dissoc ctx' :error)))})

(defn remove-queued-interceptors [ctx interceptor-name]
  (if interceptor-name
    (update ctx :queue #(->> %
                             (remove (fn [{:keys [name]}] (= name interceptor-name)))
                             (apply conj PersistentQueue/EMPTY)))
    ctx))
