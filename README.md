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

### Containerisation

To build the application in a docker container run
```shell
docker build --file infra/Dockerfile --network host --tag restaurant:dev .
```

To run the container
```shell
docker run --publish 3000:3000 restaurant:dev
```