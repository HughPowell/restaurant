#!/usr/bin/env bash

set -e

clj-kondo --lint deps.edn src test infra dev
cljfmt check deps.edn src test infra dev
clojure -X:dev:test user/verify
