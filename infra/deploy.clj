(ns deploy
  (:require [deploy.load-balancer :as load-balancer]
            [deploy.network :as network]
            [deploy.service :as service]
            [sieppari.core :as sieppari]))

(defn deploy
  [{:keys [env ssh-user hostname tag]}]
  (let [ssh-access (format "%s@%s" ssh-user hostname)
        {:keys [response]} (sieppari/execute
                             (concat
                               network/deploy-network
                               load-balancer/deploy-load-balancer
                               service/deploy-service)
                             (merge
                               (network/config ssh-access)
                               (load-balancer/config env ssh-user hostname)
                               (service/config env ssh-user hostname tag)
                               {:env env}))]
    (when-let [error (:error response)]
      (throw error))))

(comment
  (require '[git])
  (require '[build])
  (build/containerise {:tag (git/current-tag)})

  (deploy
    {:env :dev
       :tag (git/current-tag)}
    #_{:tag      "a89844e"
     :ssh-user "debian"
     :hostname "restaurant.hughpowell.net"
     :env      :prod})
  )
