# Restaurant

The restaurant management application as described in
[Code that fits in your head](https://www.oreilly.com/library/view/code-that-fits/9780137464302/), but with my personal
biases, including being written in Clojure.

## Getting started

- Clone the repo

```shell
git clone git@github.com:HughPowell/restaurant.git
```

- Start your REPL
- Use the rich comment block in the `restaurant` namespace to start the service.

## Code Analysis

Ideally all the following linters and code formatters should be integrated into your development environment for the
fastest possible feedback. If that isn't the case then you can run them from the command line.

### Linters

3 linters are used to check the code of this project [clj-kondo](https://github.com/clj-kondo/clj-kondo),
[eastwood](https://github.com/jonase/eastwood)
and [splint (the successor to kibit)](https://github.com/noahtheduke/splint). For maximum speed it is assumed the
`clj-kondo`
[pre-built binary](https://github.com/clj-kondo/clj-kondo/blob/master/doc/install.md#installation-script-macos-and-linux)
is installed on the system and accessible to your user.

```shell
clj-kondo --lint deps.edn src dev infra
```

`eastwood` and `splint` don't have pre-built binaries and are therefore run using aliases

```shell
clojure -M:dev:test:linters -m eastwood.lint
```

```shell
clojure -M:dev:test:linters -m noahtheduke.splint
```

To lint the infrastructure replace the `dev` and `test` aliases with the `build` or `upgade`.

### Code formatter

The code formatter of choice is [cljfmt](https://github.com/weavejester/cljfmt). This also comes with a pre-built binary
which is expected to be installed on the system and accessible to your user.

```shell
cljfmt check deps.edn src dev infra
```

## Infrastructure

### Uberjar

To build the application as an uberjar run

```shell
clojure -X:build uber
```

To run the uberjar

```shell
java -jar target/restaurant.jar
```

#### Observability

To run the uberjar with the OpenTelemetry agent

```shell
sudo wget -O /usr/local/lib/opentelemetry-javaagent.jar \
https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar
java \
-Djdk.tracePinnedThreads=full \
-javaagent:/usr/local/lib/opentelemetry-javaagent.jar \
-Dotel.resource.attributes=service.name=restaurant-dev \
-jar \
target/restaurant.jar
```

### Containerisation

To build the application in a docker container run

```shell
docker build --file infra/Dockerfile --network host --tag restaurant:dev .
```

To run the container

```shell
docker run --publish 3000:3000 restaurant:dev
```