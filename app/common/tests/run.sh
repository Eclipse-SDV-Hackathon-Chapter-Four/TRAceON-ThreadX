#!/usr/bin/env bash
# Host-compile and run the firmware logger pure-logic tests.
#
#   ./app/common/tests/run.sh
#
# Compiles test_logger_host.c (which #includes logger.c) on the host with
# stub headers for the hardware deps (sntp_client.h, mqtt_client.h). No ARM
# toolchain or board required.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
CC="${CC:-cc}"
OUT="$(mktemp -d)/test_logger_host"

"$CC" -std=gnu99 -Wall -Wextra -Wno-unused-parameter -g \
  -I"$HERE/stubs" \
  -I"$ROOT/app/common" \
  -I"$ROOT/deps/lib/nanoprintf/src" \
  "$HERE/test_logger_host.c" \
  -o "$OUT"

"$OUT"
