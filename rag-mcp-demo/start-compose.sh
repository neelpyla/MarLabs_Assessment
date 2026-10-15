#!/bin/sh
set -eu

retry_pull() {
  while ! "$@"; do
    echo "Image pull failed; retrying in 10 seconds..." >&2
    sleep 10
  done
}

retry_pull docker compose pull
retry_pull docker pull eclipse-temurin:17-jre

exec docker compose up --build --pull never "$@"
