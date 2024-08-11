#!/usr/bin/env bash

set -e

clj-kondo --lint deps.edn src test dev infra
clojure -M:dev:test:linters -m eastwood.lint
clojure -M:dev:test:linters -m noahtheduke.splint
clojure -M:build:upgrade:linters -m eastwood.lint
clojure -M:build:upgrade:linters -m noahtheduke.splint
cljfmt check deps.edn src test dev infra
clojure -X:test
