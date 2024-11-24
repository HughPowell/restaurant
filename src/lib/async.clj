(ns lib.async
  (:require [clojure.core.async :as async]
            [cognitect.anomalies :as-alias anomalies]
            [java-time.api :as java-time]))

(defn serialise-calls [{:keys [port timeout]} f]
  (fn [& args]
    (let [timeout-channel (async/timeout (java-time/as timeout :millis))
          out-channel (async/chan 1)
          [_ port] (async/alts!! [[port [out-channel f args]] timeout-channel] :priority true)]
      (when (= port timeout)
        (throw (ex-info "Failed to put value on channel before timeout expired"
                        {:restaurant/result ::anomalies/busy
                         :action            :put
                         :timeout           timeout
                         :function          f
                         :args              args})))
      (let [[result port] (async/alts!! [out-channel timeout-channel] :priority true)]
        (when (= port timeout)
          (throw (ex-info "Failed to put and take value on channel before timeout expired"
                          {:restaurant/result ::anomalies/busy
                           :action            :take
                           :timeout           timeout
                           :function          f
                           :args              args})))
        result))))

(comment)
