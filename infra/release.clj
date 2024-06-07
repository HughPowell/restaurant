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
                      :version  "v1.45"}))

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

(defn push [{:keys [username password name tag]}]
  (try
    (invoke-with-stream
      image-client
      {:op                   :ImagePush
       :params               {:name            (string/lower-case (format "%s:%s" name tag))
                              :tag             tag
                              :X-Registry-Auth (->> {:username username
                                                     :password password}
                                                    (json/generate-string)
                                                    (.getBytes)
                                                    (.encodeToString (Base64/getEncoder)))}
       :throw-exceptions     true
       :throw-entire-message true})
    (catch ExceptionInfo ex
      (println (slurp (:body (ex-data ex)))))))

(comment
  (require '[git])

  (push {:username ""
         :password ""
         :name "ghcr.io/hughpowell/restaurant"
         :tag (git/current-tag)})
  *e
  )
