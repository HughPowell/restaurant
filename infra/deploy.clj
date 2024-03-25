(ns deploy
  (:require [camel-snake-kebab.core :as camel-snake-kebab]
            [camel-snake-kebab.extras]
            [clj-yaml.core :as yaml]))

(defn- web-service [colour]
  {:container-name (format "%s-web" colour)
   :build          {:context    "../"
                    :dockerfile "infra/Dockerfile"
                    :network    "host"}
   :labels         ["traefik.enable=true"
                    (format "traefik.http.routers.%s-web.rule=PathPrefix(`/`)" colour)
                    (format "traefik.http.routers.%s-web.entrypoints=web" colour)
                    "traefik.http.middlewares.test-retry.retry.attempts=5"
                    "traefik.http.middlewares.test-retry.retry.initialinterval=200ms"
                    "traefik.http.services.web.loadbalancer.server.port=80"
                    "traefik.http.services.web.loadbalancer.healthCheck.path=/health"
                    "traefik.http.services.web.loadbalancer.healthCheck.interval=10s"
                    "traefik.http.services.web.loadbalancer.healthCheck.timeout=1s"]
   :networks       ["traefik"]})

(defn blue-green-configuration []
  {:version "3"
   :services {:reverse-proxy {:container-name "traefik"
                              :image          "traefik:v2.11"
                              :labels         ["traefik.http.routers.api.rule=Host(`localhost`)"
                                               "traefik.http.routers.api.service=api@internal"]
                              :networks       ["traefik"]
                              :ports          ["80:80"
                                               "127.0.0.1:8080:8080"]
                              :volumes        ["/var/run/docker.sock:/var/run/docker.sock:ro"
                                               "./traefik.yml:/traefik.yml"]}
              :blue-web        (web-service "blue")
              :green-web       (web-service "green")}
   :networks {:traefik
              {:name "traefik_webgateway"
               :external true
               :driver "bridge"}}})

(defn deploy [_]
  (spit "infra/docker-compose.yml"
        (yaml/generate-string
          (camel-snake-kebab.extras/transform-keys
            camel-snake-kebab/->snake_case_string
            (blue-green-configuration))
          :dumper-options {:flow-style :block}))
  )

(comment
  )
