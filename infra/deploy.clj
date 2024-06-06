(ns deploy
  (:require [deploy.lib.host :as host]
            [deploy.load-balancer :as load-balancer]
            [deploy.network :as network]
            [deploy.service :as service]
            [sieppari.core :as sieppari]))

(defn deploy
  [{:keys [env ssh-user hostname tag config-dir]}]
  (let [{:keys [response]} (sieppari/execute
                             (concat
                               host/ssh-session
                               network/deploy-network
                               load-balancer/deploy-load-balancer
                               service/deploy-service)
                             (merge
                               (host/config ssh-user hostname)
                               network/config
                               (load-balancer/config env ssh-user hostname config-dir)
                               (service/config env ssh-user hostname tag config-dir)
                               {:env env}))]
    (when-let [error (:error response)]
      (throw error))))

(comment
  (require '[git])

  (deploy
    #_{:env        :dev
       :config-dir "/tmp/restaurant"
       :tag        (git/current-tag)}
    {:tag      "a89844e"
     :ssh-user "debian"
     :hostname "restaurant.hughpowell.net"
     :env      :prod})
  *e
  )
