(ns deploy
  (:require [deploy.load-balancer :as load-balancer]
            [deploy.network :as network]
            [deploy.service :as service]
            [git]
            [sieppari.core :as sieppari]))

(defn deploy
  [{:keys [env ssh-user hostname tag]}]
  (let [ssh-access (format "%s@%s" ssh-user hostname)]
    (sieppari/execute
      (concat
        network/deploy-network
        load-balancer/deploy-load-balancer
        service/deploy-service)
      (merge
        (network/config ssh-access)
        (load-balancer/config env ssh-user hostname)
        (service/config env ssh-user hostname tag)
        {:env env}))))

(comment

  )
