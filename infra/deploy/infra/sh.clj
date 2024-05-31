(ns deploy.infra.sh
  (:require [clojure.java.shell :as shell]
            [clojure.string :as string]))

(defn shell-out [& args]
  (let [{:keys [exit out] :as result} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (format "Failed to execute command: %s" (string/join " " args))
                      {:args args
                       :result result})))
    out))
