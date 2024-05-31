(ns deploy.infra.interceptors
  (:require [clojure.pprint :as pprint]))

(def bulkhead {:error (fn [ctx] (pprint/pprint (:error ctx)) (dissoc ctx :error))})
