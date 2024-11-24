(ns restaurant-test
  (:refer-clojure :exclude [read])
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.test :refer [are deftest is use-fixtures]]
            [java-time.api :as java-time]
            [net.modulolotus.truegrit :as truegrit]
            [restaurant :as sut]
            [restaurant.reservation-book :as reservation-book]
            [system])
  (:import (clojure.lang IDeref)
           (java.io ByteArrayInputStream)))

(use-fixtures :once (fn [f] (system/configure-open-telemetry-logging) (f)))

(defn- ephemeral-port
  "Returns a random [ephemeral port](https://en.wikipedia.org/wiki/Ephemeral_port) number"
  []
  (+ 49152 (rand-int (- 65535 49152))))

(def ^:private maitre-d
  {:tables           [{:type :communal :seats 12}]
   :seating-duration (java-time/hours 6)
   :opens-at         (java-time/local-time 16)
   :last-seating     (java-time/local-time 21)})

(def ^:private zeroed-uuid (parse-uuid "00000000-0000-0000-0000-000000000000"))

(defn in-memory-reservation-book
  ([]
   (let [storage (atom {})]
     (reify
       reservation-book/ReservationBook
       (book [_ public-id reservation] (->> public-id
                                            (assoc reservation :id)
                                            (swap! storage assoc public-id)))
       (read [_ date] (filter
                        (fn [{:keys [at]}]
                          (let [midnight (java-time/local-date-time date 0)
                                next-day (java-time/plus midnight (java-time/days 1))]
                            (and (java-time/not-before? at midnight) (java-time/before? at next-day))))
                        (vals @storage)))
       (read-reservation [_ id]
         (get @storage id))
       IDeref
       (deref [_] @storage))))
  ([_] (in-memory-reservation-book)))

(defmacro with-http-server [[port-sym port-fn] & body]
  `(let [[~port-sym system#] ((truegrit/with-retry
                                (fn []
                                  (let [port# ~port-fn
                                        system# (system/start
                                                  {:server                  {:join? false :port port#}
                                                   :routes                  sut/routes
                                                   :maitre-d                maitre-d
                                                   :now                     (constantly
                                                                              (java-time/local-date-time 2022 04 01 20 15))
                                                   :generate-reservation-id (constantly zeroed-uuid)
                                                   :datasource              :system/none
                                                   :reservation-book        in-memory-reservation-book})]
                                    [port# system#]))
                                {:max-attempts 5}))]

     (try
       ~@body
       (finally (system/stop system#)))))

(deftest ^:characterisation home-returns-json
  (with-http-server [port (ephemeral-port)]
    (let [response (client/request {:request-method   :get
                                    :headers          {"Accept" "application/json"}
                                    :scheme           :http
                                    :server-name      "localhost"
                                    :server-port      port
                                    :throw-exceptions false})]

      (is (client/success? response))
      (is (re-find #"application/json" (get-in response [:headers "Content-Type"]))))))

(defn- post-reservation [port reservation]
  (client/request {:body             (json/write-str reservation)
                   :headers          {"Content-Type" "application/json;charset=utf-8"}
                   :request-method   :post
                   :scheme           :http
                   :server-name      "localhost"
                   :server-port      port
                   :throw-exceptions false
                   :uri              "/reservations"}))

(defn- reservation [at email name quantity]
  {:at       at
   :email    email
   :name     name
   :quantity quantity})

(deftest ^:integration post-valid-reservation
  (let [res (reservation "2023-03-10T19:00" "katinka@example.com" "Katinka Ingabogovinanana" 2)

        response (with-http-server [port (ephemeral-port)]
                   (post-reservation port res))]

    (is (client/success? response))))

(deftest ^:integration post-invalid-reservation
  (are [at email name quantity]
    (let [res (reservation at email name quantity)

          response (with-http-server [port (ephemeral-port)]
                     (post-reservation port res))]

      (client/client-error? response))

    nil "j@example.net" "Jay Xerxes" 1
    "not a date" "w@example.edu" "Wk Hd" 8
    "2023-11-30T20:01" nil "Thora" 19
    "2022-01-02T12:10" "3@example.com" "3 Beard" 0
    "2045-12-31T11:45" "git@example.com" "Gil Tan" -1))

(defn- get-reservation [port uri]
  (-> {:headers          {"Accept" "application/json"}
       :request-method   :get
       :scheme           :http
       :server-name      "localhost"
       :server-port      port
       :throw-exceptions false
       :uri              uri}
      (client/request)
      (update :body json/read-str :key-fn keyword)))

(deftest ^:integration read-successful-reservation
  (are [date email name quantity]

    (let [expected (reservation date email name quantity)

          reservation (with-http-server [port (ephemeral-port)]
                        (let [response (post-reservation port expected)
                              uri (get-in response [:headers "Location"])]
                          (get-reservation port uri)))]

      (is (client/success? reservation))
      (is (= expected (dissoc (:body reservation) :id))))

    "2023-06-09T19:10", "adur@example.net", "Adrienne Ursa", 2
    "2023-07-13T18:55", "emol@example.gov", "Emma Olsen", 5))

(defn- in-memory-system []
  (system/start
    {:server                  :system/none
     :routes                  sut/routes
     :datasource              :system/none
     :reservation-book        in-memory-reservation-book
     :maitre-d                maitre-d
     :now                     (constantly (java-time/local-date-time 2022 01 01 18 00))
     :generate-reservation-id (constantly zeroed-uuid)}))

(defn- reservation-request [at email name quantity]
  {:headers        {"content-type" "application/json"
                    "accept"       "application/json"}
   :scheme         "http"
   :remote-addr    "127.0.0.1"
   :request-method :post
   :protocol       "HTTP/1.1"
   :server-name    "localhost"
   :server-port    80
   :uri            "/reservations"
   :body           (ByteArrayInputStream. (.getBytes (json/write-str (reservation at email name quantity))))})

(deftest ^:unit post-valid-reservation-when-database-is-empty
  (let [{:keys [server reservation-book]} (in-memory-system)]

    (are [at email name quantity]
      (do
        (let [request (reservation-request at email name quantity)]

          (server request))

        (some (-> (reservation (java-time/local-date-time at) email (str name) quantity)
                  (assoc :id zeroed-uuid)
                  (hash-set))
              (vals @reservation-book)))

      "2023-11-24T19:00" "julia@example.net" "Julia Domna" 5
      "2024-02-13T18:15" "x@example.com" "Xenia Ng" 9
      "2023-08-23t16:55" "kite@example.edu" nil 2
      "2022-03-18T17:30" "shli@example.org" "Shangri La" 5)))

(deftest ^:unit overbook-attempt
  (let [{:keys [server]} (in-memory-system)]
    (-> (reservation-request "2022-03-18T17:30" "mars@example.edu" "Maria Seminova" 6)
        (server))

    (let [response (-> (reservation-request "2022-03-18T17:30" "shli@example.org" "Shangri La" 7)
                       (server))]

      (is (client/server-error? response)))))

(deftest ^:unit book-table-when-free-seating-is-available
  (let [{:keys [server]} (in-memory-system)]
    (-> (reservation-request "2022-01-02T18:15" "net@example.net" "Ned Tucker" 2)
        (server))

    (let [response (-> (reservation-request "2022-01-02T18:30" "kant@example.edu" "Katrine Nohr Troleslen" 4)
                       (server))]

      (is (client/success? response)))))

(comment
  (home-returns-json)
  (post-valid-reservation)
  (read-successful-reservation)
  (post-valid-reservation-when-database-is-empty)
  (post-invalid-reservation)
  (overbook-attempt)
  (book-table-when-free-seating-is-available))
