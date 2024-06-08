(ns release
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [contajners.core :as containers])
  (:import (clojure.lang ExceptionInfo)
           (java.util Base64)))

(def ^:private image-client
  (containers/client {:engine   :docker
                      :category :images
                      :conn     {:uri "unix:///var/run/docker.sock"}
                      :version  "v1.43"}))

(defn- invoke-with-stream [client params]
  (let [input-stream (containers/invoke client (merge {:as :stream} params))]
    (loop [data (json/parsed-seq (io/reader input-stream))]
      (when-let [line (first data)]
        (if-let [s (get line "stream")]
          (do
            (print s)
            (flush))
          (pprint/pprint line))
        (recur (rest data))))))

(defn push [{:keys [registry-username registry-password image-name tag]}]
  (try
    (invoke-with-stream
      image-client
      {:op                   :ImagePush
       :params               {:name            (string/lower-case (format "%s:%s" image-name tag))
                              :tag             tag
                              :X-Registry-Auth (->> {:username registry-username
                                                     :password registry-password}
                                                    (json/generate-string)
                                                    (.getBytes)
                                                    (.encodeToString (Base64/getEncoder)))}
       :throw-exceptions     true
       :throw-entire-message true})
    (catch ExceptionInfo ex
      (println (slurp (:body (ex-data ex))))
      (throw ex))))

(comment
  (require '[git])

  ;; production
  (push {:registry-username "<your-username>"
         :registry-password "<your-personal-access-token>"
         :image-name        "ghcr.io/hughpowell/restaurant"
         :tag               (git/current-tag)})
  *e
  )
