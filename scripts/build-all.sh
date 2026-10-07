#!/usr/bin/env bash
#
# build-all.sh — build every TRAceON component from one place.
#
#   ./scripts/build-all.sh                 # build all three
#   ./scripts/build-all.sh java python      # build a subset
#   ./scripts/build-all.sh firmware clean   # pass 'clean' to the firmware build
#   ./scripts/build-all.sh -h               # help
#
# Components:
#   firmware  -> delegates to scripts/build.sh (needs the ARM GCC toolchain)
#   java      -> mvn -B clean package        (needs Maven + a JDK)
#   python    -> telemetry-server/setup.sh   (venv + pip install)
#
# Firmware build-time config is forwarded to build.sh via the usual env vars:
#   WIFI_SSID=... WIFI_PASSWORD=... BROKER_IP=a.b.c.d MQTT_CLIENT_NAME=...
#
# Exit non-zero if any *attempted* component fails. A component whose toolchain
# is missing is SKIPPED (reported), not failed — so you can build the servers on
# a machine without the ARM toolchain.
#
# Bash-3.2-safe (macOS /bin/bash). Re-execs under bash if started by zsh/sh.

# --- ensure bash ---
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

VERBOSE=0
FIRMWARE_CLEAN=""
SELECTED=""

usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^#\{0,1\} \{0,1\}//'
    exit "${1:-0}"
}

# --- parse args: component names, 'clean'/'rebuild' (for firmware), flags ---
for arg in "$@"; do
    case "$arg" in
        -h|--help)      usage 0 ;;
        -v|--verbose)   VERBOSE=1 ;;
        clean|rebuild)  FIRMWARE_CLEAN="$arg" ;;
        firmware|java|python) SELECTED="${SELECTED} ${arg}" ;;
        *) echo "unknown argument: $arg" >&2; usage 2 ;;
    esac
done
# default: all three, in dependency-free order (servers first — they're quick)
[ -z "${SELECTED// /}" ] && SELECTED="java python firmware"

# --- bash-3.2-safe result tracking (no associative arrays) ---
set_result() { eval "RESULT_$1=\"\$2\""; }
get_result() { eval "printf '%s' \"\${RESULT_$1:-}\""; }

run_quiet() {
    # run_quiet <name> <logfile> <command...>
    local name="$1"; shift
    local log="$1"; shift
    if [ "$VERBOSE" -eq 1 ]; then
        "$@" 2>&1 | tee "$log"
        return "${PIPESTATUS[0]}"
    else
        "$@" >"$log" 2>&1
    fi
}

build_firmware() {
    local log; log="$(mktemp)"
    printf '  %-9s ' "firmware"
    # Toolchain present? (either on PATH, or ARM_GCC_PATH set)
    if ! command -v arm-none-eabi-gcc >/dev/null 2>&1 && [ -z "${ARM_GCC_PATH:-}" ]; then
        echo "SKIP (no ARM GCC toolchain; set ARM_GCC_PATH or install arm-none-eabi-gcc)"
        set_result firmware SKIP
        rm -f "$log"; return 0
    fi
    echo "building…"
    if run_quiet firmware "$log" "${SCRIPT_DIR}/build.sh" mqtt ${FIRMWARE_CLEAN:+$FIRMWARE_CLEAN}; then
        set_result firmware PASS
    else
        set_result firmware FAIL
        [ "$VERBOSE" -eq 0 ] && { echo "    --- firmware output ---"; cat "$log"; }
    fi
    rm -f "$log"
}

build_java() {
    local log; log="$(mktemp)"
    printf '  %-9s ' "java"
    if ! command -v mvn >/dev/null 2>&1; then
        echo "SKIP (no Maven on PATH)"; set_result java SKIP; rm -f "$log"; return 0
    fi
    # JAVA_HOME lesson: /usr/libexec/java_home may not see a Homebrew JDK.
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk)}"
    echo "building…"
    if run_quiet java "$log" sh -c "cd '${ROOT_DIR}/telemetry-server-java' && mvn -B clean package"; then
        set_result java PASS
    else
        set_result java FAIL
        [ "$VERBOSE" -eq 0 ] && { echo "    --- java output (tail) ---"; tail -30 "$log"; }
    fi
    rm -f "$log"
}

build_python() {
    local log; log="$(mktemp)"
    printf '  %-9s ' "python"
    if [ ! -f "${ROOT_DIR}/telemetry-server/setup.sh" ]; then
        echo "SKIP (setup.sh not found)"; set_result python SKIP; rm -f "$log"; return 0
    fi
    echo "building…"
    if run_quiet python "$log" sh -c "cd '${ROOT_DIR}/telemetry-server' && ./setup.sh"; then
        set_result python PASS
    else
        set_result python FAIL
        [ "$VERBOSE" -eq 0 ] && { echo "    --- python output (tail) ---"; tail -30 "$log"; }
    fi
    rm -f "$log"
}

echo "=========================================="
echo " TRAceON build-all"
echo "=========================================="
for c in $SELECTED; do
    case "$c" in
        firmware) build_firmware ;;
        java)     build_java ;;
        python)   build_python ;;
    esac
done

echo ""
echo "============================================================"
echo " SUMMARY"
echo "============================================================"
FAILED=0
for c in $SELECTED; do
    r="$(get_result "$c")"
    printf '  %-9s %s\n' "$c" "${r:-?}"
    [ "$r" = "FAIL" ] && FAILED=1
done
echo ""
if [ "$FAILED" -eq 0 ]; then
    echo "All attempted components built (skips are not failures)."
    exit 0
else
    echo "One or more components FAILED."
    exit 1
fi
