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

## Production configuration

The following configuration is required to deploy to production.

To push the new service image to the GitHub image registry you'll need to
[create a GitHub Personal Access Token (classic)](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#personal-access-tokens-classic)
with permissions to `write:packages`.

To deploy to the production server you'll need to
[add your SSH key](https://linuxhandbook.com/add-ssh-public-key-to-server/) to
the production server.

## All-in-one

To update the service with one call use the `infra/update-service` function.
To deploy locally use `:dev` as the `:env` value.

```shell
clojure -X:infra update-service \
:env        :dev \
:config-dir \"/tmp/restaurant\" \
:image-name \"ghcr.io/hughpowell/restaurant\" \
:tag        \"$(clojure -X:infra git/current-tag)\"
```

To deploy to the production server make sure you've worked through the
[production configuration](#production-configuration).

```shell
clojure -X:infra update-service \
:env               :prod \
:registry-username \"<github-username>\" \
:registry-password \"<github-registry-personal-access-token>\" \
:ssh-user          \"debian\" \
:hostname          \"restaurant.hughpowell.net\" \
:image-name        \"ghcr.io/hughpowell/restaurant\" \
:tag               \"$(clojure -X:infra git/current-tag)\"
```

## Individual steps

### Pre-requisites

We'll be using the GIT SHA of the latest commit for all of these steps, so we'll
record it up front.

```shell
export GIT_SHA="$(clojure -X:infra git/current-tag)"
```

### Build

Simply builds the image locally, no additional configuration needed

```shell
clojure -X:infra contaierise \
:name \"ghcr.io/HughPowell/restaurant\" \
:tag \"$GIT_SHA\"
```

### Release

Pushes the image to the GitHub registry where production builds are hosted. This
is only necessary for releasing to production.

```shell
clojure -X:infra push \
:username \"<your-github-username>\" \
:password \"<your-github-personal-access-token>\" \
:name     \"ghcr.io/HughPowell/restaurant\" \
:tag      \"$GIT_SHA\"
```

### Deploy

Deploys the whole system, either locally or to production. 

#### Development

```shell
clojure -X:infra deploy \
:env        :dev \
:config-dir \"/tmp/restaurant\" \
:image-name \"ghcr.io/hughpowell/restaurant\" \
:tag        \"$GIT_SHA\"
```

#### Production

```shell
clojure -X:infra deploy \
:env        :prod \
:ssh-user   \"debian\" \
:hostname   \"restaurant.hughpowell.net\" \
:image-name \"ghcr.io/hughpowell/restaurant\" \
:tag        \"$GIT_SHA\"
```

## TODOs
* Finer grained canary releases
* Scaling
    * Horizontal
    * Vertical
* Deployment process resilience
