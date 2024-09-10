#!/usr/bin/env bash

set -e

clj-kondo --lint deps.edn src test dev infra
echo "Unable to link with eastwood until tools.analyzer upgraded to handle \
1.12 https://clojure.atlassian.net/browse/TANAL-141"
#clojure -M:dev:test:linters -m eastwood.lint
clojure -M:dev:test:linters -m noahtheduke.splint
echo "Unable to link with eastwood until tools.analyzer upgraded to handle \
1.12 https://clojure.atlassian.net/browse/TANAL-141"
#clojure -M:build:upgrade:linters -m eastwood.lint
clojure -M:build:upgrade:linters -m noahtheduke.splint
cljfmt check deps.edn src test dev infra
clojure -X:test
