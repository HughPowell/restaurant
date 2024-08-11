(ns restaurant-test
  (:require [clj-http.client :as client]
            [clojure.test :refer [deftest is use-fixtures]]
            [restaurant :as sut]))

(use-fixtures :once (fn [f] (sut/configure-open-telemetry-logging) (f)))

(defn- successful? [response]
  (<= 200 (:status response) 299))

(defn- ephemeral-port
  "Returns an [ephemeral port](https://en.wikipedia.org/wiki/Ephemeral_port)"
  []
  (+ 49152 (rand-int (- 65535 49152))))

(deftest ^:characterisation home-is-ok
  (let [port (ephemeral-port)
        server (sut/start-server {:join? false :port port})]

    (try
      (let [response (client/request {:request-method :get
                                      :scheme         :http
                                      :server-name    "localhost"
                                      :server-port    port})]

        (is (successful? response)))
      (finally (sut/stop-server server)))))

(comment
  (home-is-ok))
