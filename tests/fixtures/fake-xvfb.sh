#!/usr/bin/env sh

set -eu

trap 'exit 0' INT TERM
while :; do
  sleep 1
done
