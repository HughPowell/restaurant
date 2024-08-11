(ns user
  (:require [kaocha.repl]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(comment
  (kaocha.repl/run-all))
