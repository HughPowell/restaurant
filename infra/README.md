# Restaurant infrastructure

The restaurant service is deployed in a docker container and is fronted by
a [traefik](https://doc.traefik.io/traefik/) load balancer. This allows for
(simplified) canary releases by bringing up a new version of the service,
testing it is healthy and then shutting and removing the old version.

There are 3 steps to getting a new version of the restaurant service out into
production. Each of these can be run from a dev machine with a small amount of
configuration.

Each of the steps can be run from the rich comment or the equivalent command
line, as shown below.

## Pre-requisites

We'll be using the GIT SHA of the latest commit for all of these steps, so we'll
record it up front.

```shell
export GIT_SHA="$(clojure -X:build git/current-tag)"
```

## Build

Simply builds the image locally, no additional configuration needed

```shell
clojure -X:build contaierise \
:name \"ghcr.io/HughPowell/restaurant\" \
:tag \"$GIT_SHA\"
```

## Release

Pushes the image to the GitHub registry where production builds are hosted.
For this you'll need to
[create a GitHub Personal Access Token (classic)](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#personal-access-tokens-classic)
with permissions to `write:packages`. This is only necessary for releasing to
production.

```shell
clojure -X:release push \
:username \"<your-github-username>\" \
:password \"<your-github-personal-access-token>\" \
:name     \"ghcr.io/HughPowell/restaurant\" \
:tag      \"$GIT_SHA\"
```

## Deploy

Deploys the whole system, either locally or to production. If deploying to
production then you'll need to
[add your SSH key](https://linuxhandbook.com/add-ssh-public-key-to-server/) to
the production server.

### Development

```shell
clojure -X:deploy deploy \
:env        :dev
:config-dir \"/tmp/restaurant\"
:image-name \"ghcr.io/hughpowell/restaurant\"
:tag        \"$GIT_SHA\"
```

### Production

```shell
clojure -X:deploy deploy \
:env        :prod
:ssh-user   \"debian\"
:hostname   \"restaurant.hughpowell.net\"
:image-name \"ghcr.io/hughpowell/restaurant\"
:tag        \"$GIT_SHA\"
```

## TODOs
* Finer grained canary releases
* Scaling
    * Horizontal
    * Vertical
* Deployment process resilience
