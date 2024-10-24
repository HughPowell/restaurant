(ns user
  (:require [eastwood.lint :as eastwood]
            [kaocha.repl]
            [noahtheduke.splint.runner :as splint]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(defn run-splint []
  (let [result (splint/run ["src" "dev" "infra" "test"])]
    (when-not (zero? (:exit result))
      (throw (ex-info (:message result) result)))))

(defn run-eastwood []
  (let [result (eastwood/lint {:source-paths ["src" "dev" "infra"] :test-paths ["test"]})]
    (when (or (:err result) (seq (:warnings result)))
      (throw (ex-info "Eastwood linting failed" result)))))

(defn lint [& _]
  (println "Linting with Splint")
  (run-splint)
  (println "Unable to lint with eastwood until tools.analyzer upgraded to handle 1.12"
           "https://clojure.atlassian.net/browse/TANAL-141")
  #_(run-eastwood))

(defn verify [& _]
  (lint)
  (kaocha.repl/run-all))

(comment
  (verify)
  (run-splint)
  (run-eastwood)
  (lint)
  (kaocha.repl/run-all))
