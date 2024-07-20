(ns build
  (:require [clojure.tools.build.api :as b]))

(def target-dir "target")
(def class-dir (format "%s/classes" target-dir))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path target-dir}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file (format "%s/restaurant.jar" target-dir)
           :basis     @basis
           :main      'restaurant}))
