(ns lib.http
  (:require [cognitect.anomalies :as-alias anomalies]
            [java-time.api :as java-time]
            [lib.malli]
            [malli.core :as malli]
            [malli.error]
            [malli.experimental.time.transform :as malli.time.transform]
            [malli.transform]
            [reitit.core :as reitit]
            [ring.util.response :as response])
  (:import (clojure.lang ExceptionInfo)))

(defn- internal-server-error [body]
  {:status  500
   :headers {}
   :body    body})

(defn- service-unavailable [duration body]
  {:status  503
   :headers {"Retry-After" (java-time/as duration :seconds)}
   :body    body})

(defn- ->response [router result]
  (case (:restaurant/result result)
    ;; errors
    ::anomalies/incorrect (response/bad-request (dissoc result :restaurant/result))
    ::anomalies/fault (internal-server-error (dissoc result :restaurant/result))
    ::anomalies/busy (service-unavailable (:timeout result)
                                          {:reason             "Request timed out"
                                           :timeout-in-seconds (java-time/as (:timeout result) :seconds)})

    ;; successes
    :restaurant/ok (response/response (dissoc result :restaurant/result))
    :restaurant/created (let [{:keys [resource params]} result]
                          (-> router
                              (reitit/match-by-name resource params)
                              (reitit/match->path)
                              (response/created)))))

(defn wrap-response [handler {:keys [router]}]
  (fn [request]
    (->response router (handler request))))

(defn- parse-request-body [schema {:keys [body] :as _request}]
  (try
    (malli/coerce schema body (malli.transform/transformer
                                malli.transform/default-value-transformer
                                malli.time.transform/time-transformer))
    (catch ExceptionInfo e
      (-> e
          (ex-data)
          (get-in [:data :explain])
          (malli.error/humanize)
          (malli.error/with-spell-checking)
          (merge {:restaurant/result ::anomalies/incorrect})))))

(defn wrap-parse-request-body [handler {:keys [schema]}]
  (fn [request]
    (let [parsed-body (parse-request-body schema request)]
      (if (and (map? parsed-body) (:restaurant/result parsed-body))
        parsed-body
        (handler parsed-body)))))
