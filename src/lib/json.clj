(ns lib.json
  (:require [clojure.data.json :as json]
            [java-time.api :as java-time])
  (:import (java.time LocalDate LocalDateTime)))

(defn- write-local-date-time [^LocalDateTime x ^Appendable out _options]
  (.append out \")
  (.append out ^String (java-time/format "yyyy-MM-dd'T'HH:mm" x))
  (.append out \"))

(extend LocalDateTime json/JSONWriter {:-write write-local-date-time})

(defn- write-local-date [^LocalDate x ^Appendable out _options]
  (.append out \")
  (.append out ^String (java-time/format "yyyy-MM-dd" x))
  (.append out \"))

(extend LocalDate json/JSONWriter {:-write write-local-date})
