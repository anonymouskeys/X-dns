#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac -encoding UTF-8 -d "$classes" \
  app/src/main/java/com/anonymouskeys/xdns/RecoveryGeneration.java \
  app/src/main/java/com/anonymouskeys/xdns/ProbePolicy.java \
  tests/java/com/anonymouskeys/xdns/RecoveryRegression.java
java -cp "$classes" com.anonymouskeys.xdns.RecoveryRegression
