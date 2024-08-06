# Restaurant

The restaurant management application as described in
[Code that fits in your head](https://www.oreilly.com/library/view/code-that-fits/9780137464302/), but with my personal
biases, including being written in Clojure.

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
java -javaagent:/usr/local/lib/opentelemetry-javaagent.jar -Dotel.resource.attributes=service.name=restaurant-dev -jar target/restaurant.jar
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