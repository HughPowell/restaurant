(ns build
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.tools.build.api :as b]
            [contajners.core :as containers]
            [git])
  (:import (java.io PipedInputStream PipedOutputStream)
           (java.nio.file Files)
           (org.apache.commons.compress.archivers.tar TarArchiveOutputStream)))

;; Uberjar

(def ^:private target-dir "target")
(def ^:private class-dir (format "%s/classes" target-dir))

;; delay to defer side effects (artifact downloads)
(def ^:private basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path target-dir}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file (format "%s/restaurant.jar" target-dir)
           :basis     @basis
           :main      'restaurant}))

;; Docker Image

(def ^:private build-client
  (containers/client {:engine   :docker
                      :category :build
                      :conn     {:uri "unix:///var/run/docker.sock"}
                      :version  "v1.45"}))

(defn- build-files []
  (let [{:keys [paths aliases]} (edn/read-string (slurp "deps.edn"))
        dirs (set/union (set paths) (set (get-in aliases [:build :paths])))]
    (->> dirs
         (mapcat (fn [dir] (file-seq (fs/file dir))))
         (remove (fn [file] (fs/directory? file)))
         (map str)
         (cons "deps.edn"))))

(defn- ->tar-input-stream [filenames]
  (let [piped-input-stream (PipedInputStream.)
        piped-output-stream (PipedOutputStream. piped-input-stream)]
    (future
      (with-open [archive (TarArchiveOutputStream. piped-output-stream)]
        (run!
          (fn [filename]
            (let [archive-entry (.createArchiveEntry archive (fs/file filename) filename)]
              (.putArchiveEntry archive archive-entry)
              (Files/copy (fs/path filename) archive)
              (.closeArchiveEntry archive)))
          filenames)))
    piped-input-stream))

(defn containerise [{:keys [name tag] :or {name "net.hughpowell/restaurant"}}]
  (let [name:tag (format "%s:%s" name (if tag tag (git/current-tag)))
        input-stream (containers/invoke build-client {:op                   :ImageBuild
                                                      :params               {:networkmode "host"
                                                                             :dockerfile  "infra/Dockerfile"
                                                                             :t           name:tag}
                                                      :data                 (->tar-input-stream (build-files))
                                                      :as                   :stream
                                                      :throw-exceptions     true
                                                      :throw-entire-message true})]
    (loop [data (json/parsed-seq (io/reader input-stream))]
      (when-let [line (first data)]
        (if-let [s (get line "stream")]
          (do
            (print s)
            (flush))
          (clojure.pprint/pprint line))
        (recur (rest data))))))

(comment
  (require '[git])
  (containerise {:tag (git/current-tag)})

  )
