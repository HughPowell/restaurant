(ns git
  (:require [clojure.java.shell :as shell]
            [clojure.string :as string]))

(defn current-tag
  ([_] (println (current-tag)))
  ([]
   (let [{:keys [exit out] :as response} (shell/sh "git" "rev-parse" "--short" "HEAD")]
     (when-not (zero? exit)
       (throw (ex-info "Failed to generate git tag" response)))
     (first (string/split-lines out)))))
