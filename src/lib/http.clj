(ns lib.http
  (:require [cognitect.anomalies :as-alias anomalies]
            [reitit.core :as reitit]
            [ring.util.response :as response]))

(defn- internal-server-error [body]
  {:status  500
   :headers {}
   :body    body})

(defn- ->response [router result]
  (case (:restaurant/result result)
    ;; errors
    ::anomalies/incorrect (response/bad-request (dissoc result :restaurant/result))
    ::anomalies/fault (internal-server-error (dissoc result :restaurant/result))

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
