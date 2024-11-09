(ns user
  (:require [eastwood.lint :as eastwood]
            [java-time.api :as java-time]
            [kaocha.repl]
            [noahtheduke.splint.runner :as splint]
            [restaurant]
            [shared]
            [system]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(system/configure-open-telemetry-logging)

(def system nil)

(defn start-system! []
  (if system
    (println "System already started!")
    (alter-var-root #'system (constantly
                               (system/start {:server                  {:port 3000 :join? false}
                                              :routes                  restaurant/routes
                                              :maitre-d                {:tables           [{:type :communal :seats 12}]
                                                                        :seating-duration (java-time/hours 6)
                                                                        :opens-at         (java-time/local-time 18)
                                                                        :last-seating     (java-time/local-time 21)}
                                              :now                     java-time/local-date-time
                                              :generate-reservation-id random-uuid
                                              :reservation-book        (shared/in-memory-reservation-book)})))))

(defn stop-system! []
  (when system
    (system/stop system)
    (alter-var-root #'system (constantly nil))))

(defn restart-system! []
  (stop-system!)
  (start-system!))

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
  (restart-system!)

  (verify)
  (run-splint)
  (run-eastwood)
  (lint)
  (kaocha.repl/run-all))
