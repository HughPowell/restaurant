(ns lib.json
  (:require [clojure.data.json :as json]
            [java-time.api :as java-time])
  (:import (java.time LocalDateTime)))

(defn- write-local-date-time [^LocalDateTime x ^Appendable out _options]
  (.append out \")
  (.append out ^String (java-time/format "yyyy-MM-dd'T'HH:mm" x))
  (.append out \"))

(extend LocalDateTime json/JSONWriter {:-write write-local-date-time})
