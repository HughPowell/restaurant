(ns docker-file-upgrade
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import (java.util.regex Matcher)))

(defn- docker-layers [docker-file]
  (with-open [reader (io/reader docker-file)]
    (->> reader
         (line-seq)
         (map #(rest (re-find #"FROM\s+([^:]+):([^\s]+)" %)))
         (filter seq)
         (doall))))

(defn- docker-tags [docker-hub-url-fmt repository]
  (loop [results []
         url (format docker-hub-url-fmt repository)]
    (let [body (-> (client/get url)
                   (:body)
                   (json/parse-string keyword))]
      (if (:next body)
        (recur (into results (:results body)) (:next body))
        (into results (:results body))))))

(defn- latest-docker-tag [tag results]
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

(defn- replace-in-file [docker-file match replacement]
  (with-open [reader (io/reader docker-file)]
    (as-> reader $
      (line-seq $)
      (map (fn [line] (string/replace line match replacement)) $)
      (interleave $ (repeat "\n"))
      (string/join $)
      (spit docker-file $))))

(defn- upgrade-docker-tags [docker-file docker-hub-url-fmt]
  (let [docker-layers' (docker-layers docker-file)]
    (run!
      (fn [[repository tag]]
        (->> repository
             (docker-tags docker-hub-url-fmt)
             (latest-docker-tag tag)
             (replace-in-file docker-file tag)))
      docker-layers')))

(defn- github-binaries [docker-file]
  (with-open [reader (io/reader docker-file)]
    (->> reader
         (line-seq)
         (map (fn [line] (re-find #"https://github.com/([^/]+)/([^/]+)/releases/download/([^/]+)/" line)))
         (filter seq)
         (doall))))

(defn- github-tags [github-url-fmt owner repo]
  (loop [tags []
         url (format github-url-fmt owner repo)]
    (let [response (client/get url)
          response-tags (-> response
                            (:body)
                            (json/parse-string keyword))]
      (if (get-in response [:links :next])
        (recur (into tags response-tags) (get-in response [:links :next :href]))
        (into tags response-tags)))))

(defn- latest-github-tag [tags]
  (->> tags
       (map (fn [{:keys [name]}]
              [(->> name
                    (re-seq #"\d+")
                    (mapv (fn [n] (Integer/parseInt n))))
               name]))
       (sort-by first (fn [a b] (if-let [[result] (seq (remove zero? (map compare a b)))]
                                  result
                                  0)))
       (last)
       (second)))

(defn- upgrade-github-tags [docker-file github-url-fmt]
  (let [github-binaries' (github-binaries docker-file)]
    (run!
      (fn [[url owner repo version]]
        (->> (github-tags github-url-fmt owner repo)
             (latest-github-tag)
             (string/replace url version)
             (replace-in-file docker-file url)))
      github-binaries')))

(defn upgrade [_]
  (let [docker-file "infra/Dockerfile"
        docker-hub-url-fmt "https://registry.hub.docker.com/v2/namespaces/library/repositories/%s/tags?page=1&page_size=100"
        github-url-fmt "https://api.github.com/repos/%s/%s/tags"]
    (upgrade-docker-tags docker-file docker-hub-url-fmt)
    (upgrade-github-tags docker-file github-url-fmt)))

(comment
  (upgrade nil)
  )
