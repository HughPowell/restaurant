(ns deploy
  (:require [clojure.string :as string]
            [deploy.lib.docker :as docker]
            [deploy.lib.host :as host]
            [deploy.load-balancer :as load-balancer]
            [deploy.network :as network]
            [deploy.service :as service]
            [sieppari.core :as sieppari]))

(defn deploy
  [{:keys [env ssh-user hostname tag image-name config-dir]}]
  (let [{:keys [response]} (sieppari/execute
                             (concat
                               host/ssh-session
                               network/deploy-network
                               load-balancer/deploy-load-balancer
                               service/deploy-service)
                             (let [host-config (host/config ssh-user hostname)
                                   docker-config (docker/config host-config)
                                   network-config (network/config docker-config)
                                   load-balancer-config (load-balancer/config
                                                          env
                                                          host-config
                                                          docker-config
                                                          network-config
                                                          config-dir)
                                   service-config (service/config
                                                    env
                                                    docker-config
                                                    network-config
                                                    load-balancer-config
                                                    (string/lower-case image-name)
                                                    tag)]
                               (merge
                                 service-config
                                 {:env env})))]
    (when-let [error (:error response)]
      (throw error))))

(comment
  (require '[git])

  (deploy
    ;; dev
    {:env        :dev
     :config-dir "/tmp/restaurant"
     :image-name "net.hughpowell/restaurant"
     :tag        (git/current-tag)}
    ;; production
    #_{:tag        "a89844e"
       :ssh-user   "ubuntu"
       :hostname   "restaurant.hughpowell.net"
       :image-name "ghcr.io/hughpowell/restaurant"
       :env        :prod})
  *e
  )
