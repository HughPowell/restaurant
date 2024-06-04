(ns deploy
  (:require [deploy.lib.host :as host]
            [deploy.load-balancer :as load-balancer]
            [deploy.network :as network]
            [deploy.service :as service]
            [sieppari.core :as sieppari]))

(defn deploy
  [{:keys [env ssh-user hostname tag]}]
  (let [ssh-access (format "%s@%s" ssh-user hostname)
        {:keys [response]} (sieppari/execute
                             (concat
                               host/ssh-session
                               network/deploy-network
                               load-balancer/deploy-load-balancer
                               service/deploy-service)
                             (merge
                               (host/config ssh-user hostname)
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
    #_{:env :dev
       :tag (git/current-tag)}
    {:tag      "a89844e"
     :ssh-user "debian"
     :hostname "restaurant.hughpowell.net"
     :env      :prod})
  *e
  )
