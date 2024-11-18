(ns user
  (:require [clj-http.client :as http-client]
            [clj-test-containers.core :as test-containers]
            [clojure.data.json :as json]
            [data-migrations]
            [eastwood.lint :as eastwood]
            [honey.sql :as sql]
            [java-time.api :as java-time]
            [kaocha.repl]
            [next.jdbc :as jdbc]
            [noahtheduke.splint.runner :as splint]
            [restaurant]
            [restaurant.reservation-book :as reservation-book]
            [system]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(system/configure-open-telemetry-logging)

(def database-password "database-password")
(defn container [] (-> {:image-name    "postgres:17.0"
                        :exposed-ports [5432]
                        :env-vars      {"POSTGRES_PASSWORD" database-password}}
                       (test-containers/create)
                       (test-containers/start!)))

(defn datasource [container]
  {:adapter       "postgresql"
   :database-name "postgres"
   :username      "postgres"
   :password      database-password
   :server-name   (:host container)
   :port-number   (get (:mapped-ports container) 5432)})

(defn query [container f sql]
  (let [datasource (system/start-datasource (datasource container))]
    (try
      (data-migrations/migrate datasource)
      (f datasource (sql/format sql))
      (finally
        (system/stop-datasource datasource)))))

(def system nil)

(defn start-system! []
  (if system
    (println "System already started!")
    (alter-var-root #'system (constantly
                               (system/start
                                 (let [database (container)]
                                   {:server                  {:port 3000 :join? false}
                                    :routes                  restaurant/routes
                                    :maitre-d                {:tables           [{:type :communal :seats 12}]
                                                              :seating-duration (java-time/hours 6)
                                                              :opens-at         (java-time/local-time 18)
                                                              :last-seating     (java-time/local-time 21)}
                                    :now                     java-time/local-date-time
                                    :generate-reservation-id random-uuid
                                    :database                database
                                    :datasource              (datasource database)
                                    :reservation-book        reservation-book/reservation-book}))))))

(defn stop-system! []
  (when system
    (system/stop system)
    (test-containers/stop! (:database system))
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
  (stop-system!)

  (def my-container (container))
  (query my-container
         jdbc/execute!
         {:select [1]})
  (test-containers/stop! my-container)

  (-> (http-client/get "http://localhost:3000")
      (update :body json/read-str :key-fn keyword))

  (verify)
  (run-splint)
  (run-eastwood)
  (lint)
  (kaocha.repl/run-all))
