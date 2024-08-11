(ns restaurant-test
  (:require [cheshire.core]
            [clj-http.client :as client]
            [clojure.test :refer [deftest is use-fixtures]]
            [restaurant :as sut]))

(use-fixtures :once (fn [f] (sut/configure-open-telemetry-logging) (f)))

(defn- ephemeral-port
  "Returns a random [ephemeral port](https://en.wikipedia.org/wiki/Ephemeral_port) number"
  []
  (+ 49152 (rand-int (- 65535 49152))))

(deftest ^:characterisation home-returns-json
  (let [port (ephemeral-port)
        server (sut/start-server {:join? false :port port})]

    (try
      (let [response (client/request {:request-method   :get
                                      :scheme           :http
                                      :server-name      "localhost"
                                      :server-port      port
                                      :as               :auto
                                      :throw-exceptions false})]

        (is (client/success? response))
        (is (= :application/json (:content-type response))))
      (finally (sut/stop-server server)))))

(comment
  (home-returns-json))
