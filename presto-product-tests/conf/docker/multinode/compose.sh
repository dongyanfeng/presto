#!/usr/bin/env bash

docker-compose \
-f ${BASH_SOURCE%/*}/../common/standard.yml \
-f ${BASH_SOURCE%/*}/../common/jdbc_db.yml \
-f ${BASH_SOURCE%/*}/docker-compose.yml \
"$@"
