(ns infra
  (:require [build]
            [deploy]
            [release]))

(defn ^{:arglists '([{:keys [env registry-username registry-password ssh-user hostname image-name tag]}]
                    [{:keys [env config-dir image-name tag]}])}
  update-service
  "Build, release and deploy a new version of the service.

  To deploy the service on a local box pass :dev as the value of :env. The
  following values will need to be provided:
  :config-dir - directory to store service config (e.g. /tmp/restaurant)
  :image-name - fully qualified name of the image (e.g. ghcr.io/hughpowell/restaurant)
  :tag - the short Git SHA the image represent (e.g. b29fa63)

  To deploy the service to production pass :prod as the value of :env. The
  following values will need to be provided:
  :registry-username - username of docker registry user with write permissions
  :registry-password - password of docker registry user
  :ssh-user - username that has SSH access to production server
  :hostname - hostname of the production server
  :image-name - fully qualified name of the image (e.g. ghcr.io/hughpowell/restaurant)
  :tag - the short Git SHA the image represent (e.g. b29fa63)"
  [args]
  (build/containerise args)
  (when-not (= :dev (:env args))
    (release/push args))
  (deploy/deploy args))

(comment
  (require '[git])

  ;; Deploy the current version of the service
  ;; dev
  (update-service {:env        :dev
                   :config-dir "/tmp/restaurant"
                   :image-name "ghcr.io/hughpowell/restaurant"
                   :tag        (git/current-tag)})

  ;; prod
  (update-service {:env               :prod
                   :registry-username "<github-username>"
                   :registry-password "<github-registry-personal-access-token>"
                   :ssh-user          "ubuntu"
                   :hostname          "restaurant.hughpowell.net"
                   :image-name        "ghcr.io/hughpowell/restaurant"
                   :tag               (git/current-tag)})
  )
