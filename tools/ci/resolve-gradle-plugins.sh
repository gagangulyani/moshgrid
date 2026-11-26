#!/usr/bin/env bash
# Resolves Gradle plugins and the build's configuration before the real build, retrying with a
# fresh lookup when a repository download fails. CI runners sometimes get "plugin was not found"
# for an artifact that exists (a dropped or throttled download); without this, one bad download
# fails the whole run. Changes nothing in the build output.
set -euo pipefail
cd "$(dirname "$0")/../.."
for attempt in 1 2 3; do
  extra=()
  [ "$attempt" -gt 1 ] && extra=(--refresh-dependencies)
  if ./gradlew --no-daemon --quiet ${extra[@]+"${extra[@]}"} help >/dev/null; then exit 0; fi
  if [ "$attempt" -eq 3 ]; then
    echo "error: could not resolve Gradle plugins after 3 attempts; network detail:" >&2
    ./gradlew --no-daemon --info --refresh-dependencies help 2>&1 \
      | grep -iE "could not (get|head|resolve)|status code|unknown ?host|timed out|connect|ssl|proxy" | head -20 >&2 || true
    exit 1
  fi
  echo "Gradle plugin resolution failed (attempt $attempt); retrying in $((attempt * 20))s" >&2
  sleep $((attempt * 20))
done
