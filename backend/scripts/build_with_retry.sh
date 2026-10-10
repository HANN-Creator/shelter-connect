#!/usr/bin/env bash
# Preserve Gradle's exit status and diagnostics; retry only repository rate limits.
set -uo pipefail
build_log=$(mktemp)
trap 'rm -f "$build_log"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for attempt in 1 2 3; do
    ./gradlew --no-daemon --max-workers=2 --console=plain "$@" 2>&1 | tee "$build_log"
    result=${PIPESTATUS[0]}
    if [ "$result" -eq 0 ]; then
        exit 0
    fi
    if ! grep -Eq 'Could not (GET|HEAD).*Received status code 429' "$build_log" \
        || grep -Eq 'Compilation failed|error:|Received status code (400|401|403|404)' "$build_log"; then
        echo 'Build failed without a retryable repository rate limit; stopping.' >&2
        exit "$result"
    fi
    if [ "$attempt" -eq 3 ]; then
        echo 'Repository rate limit persists after 3 attempts; keeping the failed build status.' >&2
        exit "$result"
    fi
    if [ "$attempt" -eq 1 ]; then delay=60; else delay=180; fi
    echo "Repository returned HTTP 429; waiting ${delay}s before attempt $((attempt + 1))/3. Existing downloads are retained." >&2
    sleep "$delay" || exit $?
done
