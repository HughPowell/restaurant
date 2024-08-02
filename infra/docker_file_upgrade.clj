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

(defn- paged-results [url next-fn results-fn]
  (loop [results []
         url url]
    (let [response (-> url
                       (client/get)
                       (update :body json/parse-string keyword))
          next (next-fn response)
          results' (results-fn response)]
      (if next
        (recur (into results results') next)
        (into results results')))))

(defn- docker-tags [docker-hub-url-fmt repository]
  (paged-results (format docker-hub-url-fmt repository) #(get-in % [:body :next]) #(get-in % [:body :results])))

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
  (as-> docker-file $
    (slurp $)
    (string/replace $ match replacement)
    (spit docker-file $)))

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

(defn- github-tags [github-url-fmt owner repo]
  (paged-results (format github-url-fmt owner repo) #(get-in % [:links :next :href]) :body))

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
