#!/usr/bin/env bash
# Runs the host tests on a desktop JDK (no Android needed): the pure-Java classes only.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SRC=src/io/github/vladislavekimtcov/acrylicweather
javac -nowarn -d "$OUT" $SRC/Time.java $SRC/Units.java $SRC/Wmo.java $SRC/Places.java tests/HostTests.java
java -cp "$OUT" io.github.vladislavekimtcov.acrylicweather.HostTests
