#!/usr/bin/env bash
#
# run-tests.sh — run the test suites for all three TRAceON components.
#
#   ./scripts/run-tests.sh [-v|--verbose] [firmware|python|java]...
#
# With no suite args, runs all three. Pass one or more names to run a subset,
# e.g. `./scripts/run-tests.sh python java`.
#
# Output:
#   default   - quiet: one status line per suite; a suite's full output is shown
#               only if that suite FAILS. Always prints the final summary.
#   -v/--verbose - stream every suite's full output live.
#
# Each suite runs independently (one failing suite does not stop the others);
# exits non-zero if ANY suite failed.
#
#   firmware - host-compiled pure-logic tests (app/common/tests/run.sh)
#   python   - pytest (telemetry-server/)
#   java     - JUnit 5 via Maven (telemetry-server-java/)
#
# See TESTING.md for what each suite covers.

# Re-exec under bash if started by another shell (zsh/sh) — we use bash arrays
# and $'...'. Normal `./scripts/run-tests.sh` already uses the bash shebang.
if [ -z "${BASH_VERSION:-}" ]; then
  exec bash "$0" "$@"
fi

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

VERBOSE=0
SUITES=()
for arg in "$@"; do
  case "$arg" in
    -v|--verbose) VERBOSE=1 ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^#\{0,1\} \{0,1\}//'
      exit 0 ;;
    firmware|python|java) SUITES+=("$arg") ;;
    *) echo "unknown argument: $arg (use -v/--verbose, -h/--help, or firmware|python|java)" >&2; exit 2 ;;
  esac
done
[ "${#SUITES[@]}" -eq 0 ] && SUITES=(firmware python java)

# bash-3.2 compatible result tracking: accumulate "suite=STATUS" lines.
RESULTS=""
set_result() { RESULTS="${RESULTS}$1=$2"$'\n'; }
get_result() { printf '%s\n' "$RESULTS" | awk -F= -v k="$1" '$1==k{print $2}'; }

# run_suite <name> <title> <command...>
# Verbose: stream output live. Quiet: capture; print only on failure.
run_suite() {
  local name="$1" title="$2"; shift 2
  if [ "$VERBOSE" -eq 1 ]; then
    printf '\n============================================================\n'
    echo " ${title}"
    printf '============================================================\n'
    if ( "$@" ); then set_result "$name" PASS; else set_result "$name" FAIL; fi
  else
    printf '  %-9s running… ' "$name"
    local log; log="$(mktemp)"
    if ( "$@" ) >"$log" 2>&1; then
      echo "PASS"; set_result "$name" PASS
    else
      echo "FAIL"; set_result "$name" FAIL
      echo "    ---- output (${name}) ----"
      sed 's/^/    /' "$log"
      echo "    ---------------------------"
    fi
    rm -f "$log"
  fi
}

# ---- Suite commands (each is a function so run_suite can invoke it) ----
_firmware() {
  [ -x app/common/tests/run.sh ] || { echo "app/common/tests/run.sh not found"; return 127; }
  ./app/common/tests/run.sh
}
_python() {
  command -v python3 >/dev/null 2>&1 || { echo "python3 not found"; return 127; }
  cd telemetry-server
  if [ ! -d .venv ]; then
    ./setup.sh >/dev/null && . .venv/bin/activate && pip install -q -r requirements-dev.txt
  else
    . .venv/bin/activate
    python -c "import pytest" 2>/dev/null || pip install -q -r requirements-dev.txt
  fi
  if [ "${VERBOSE:-0}" -eq 1 ]; then
    # Override the ini's addopts=-q and show one line per test case.
    python -m pytest -o addopts= -v
  else
    python -m pytest
  fi
}
_java() {
  command -v mvn >/dev/null 2>&1 || { echo "mvn not found"; return 127; }
  cd telemetry-server-java
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo /opt/homebrew/opt/openjdk)}"
  if [ "${VERBOSE:-0}" -eq 1 ]; then
    # Plain console reporting lists each test (per class) without writing files.
    mvn -B test -Dsurefire.useFile=false -Dsurefire.reportFormat=plain
  else
    mvn -B test
  fi
}

echo "Running test suites: ${SUITES[*]}  (verbose=$VERBOSE)"
for s in "${SUITES[@]}"; do
  case "$s" in
    firmware) run_suite firmware "FIRMWARE — host pure-logic tests" _firmware ;;
    python)   run_suite python   "PYTHON — pytest (telemetry-server)" _python ;;
    java)     run_suite java     "JAVA — JUnit 5 via Maven (telemetry-server-java)" _java ;;
  esac
done

# ---- Summary ----
printf '\n============================================================\n'
echo " SUMMARY"
printf '============================================================\n'
fail=0
for s in "${SUITES[@]}"; do
  r="$(get_result "$s")"; [ -z "$r" ] && r="?"
  printf "  %-9s %s\n" "$s" "$r"
  [ "$r" = "FAIL" ] && fail=1
done
echo
if [ "$fail" -eq 0 ]; then echo "All selected suites passed."; else echo "One or more suites FAILED (see output above)."; fi
exit $fail
