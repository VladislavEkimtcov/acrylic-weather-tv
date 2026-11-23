#!/usr/bin/env bash
# Installs Acrylic Weather on the TV over adb (building it first with --build).
#   ./install.sh [--build] [places.conf]
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
PKG=io.github.vladislavekimtcov.acrylicweather
adb_() { adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
if [[ ${1:-} == --build ]]; then ./build.sh; shift; fi
[[ -f build/acrylic-weather.apk ]] || { echo "no build/acrylic-weather.apk: run ./build.sh (or ./install.sh --build)" >&2; exit 1; }
adb_ install -r build/acrylic-weather.apk
if [[ -n ${1:-} ]]; then
  adb_ shell mkdir -p "/sdcard/Android/media/$PKG"
  adb_ push "$1" "/sdcard/Android/media/$PKG/places.conf"
fi
adb_ shell am start -n "$PKG/.Main" >/dev/null
echo "installed and opened $PKG"
