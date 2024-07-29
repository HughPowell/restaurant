(ns docker-file-upgrade
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.util.regex Matcher)))

(defn- libraries [docker-file]
  (with-open [reader (io/reader docker-file)]
    (->> reader
         (line-seq)
         (map #(rest (re-find #"FROM\s+([^:]+):([^\s]+)" %)))
         (filter seq)
         (doall))))

(defn- tags [docker-hub-url-fmt repository]
  (loop [results []
         url (format docker-hub-url-fmt repository)]
    (let [body (-> (client/get url)
                   (:body)
                   (json/parse-string keyword))]
      (if (:next body)
        (recur (into results (:results body)) (:next body))
        (into results (:results body))))))

(defn- latest-tag [tag results]
  (let [version-pattern-text "\\d+[\\.\\d+]*"
        version-pattern (re-pattern version-pattern-text)
        name-pattern (re-pattern (string/replace tag version-pattern (Matcher/quoteReplacement version-pattern-text)))]
    (->> results
         (map (fn [{:keys [name]}] (re-find name-pattern name)))
         (filter seq)
         (map (fn [name]
                [(->> name
                      (re-seq version-pattern)
                      (mapcat #(string/split % #"\."))
                      (mapv (fn [n] (Integer/parseInt n))))
                 name]))
         ;; There are some weird tags like 22_36 so this is a hack
         (remove (fn [[index]] (<= (count index) 2)))
         (sort-by first (fn [a b] (if-let [[result] (seq (remove zero? (map compare a b)))]
                                    result
                                    0)))
         (last)
         (second))))

(defn- upgrade-tag [docker-file current-tag new-tag]
  (with-open [reader (io/reader docker-file)]
    (as-> reader $
      (line-seq $)
      (map (fn [line] (string/replace line current-tag new-tag)) $)
      (interleave $ (repeat "\n"))
      (string/join $)
      (spit docker-file $))))

(defn upgrade [_]
  (let [docker-file "infra/Dockerfile"
        docker-hub-url-fmt "https://registry.hub.docker.com/v2/namespaces/library/repositories/%s/tags?page=1&page_size=100"
        libraries' (libraries docker-file)]
    (run!
      (fn [[repository tag]]
        (->> repository
             (tags docker-hub-url-fmt)
             (latest-tag tag)
             (upgrade-tag docker-file tag)))
      libraries')))

(comment
  )
