#!/usr/bin/env bash
# Run only inside Dockerfile.dependencies, after Gradle has exited.
set -euo pipefail
test "$PWD" = /build
test ! -e /export
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/gradle-home/caches" "$stage/gradle-home/wrapper" /export
# Explicit allowlist: no credentials, init scripts, daemon logs, source or classes.
cp -a /root/.gradle/caches/modules-2 "$stage/gradle-home/caches/"
cp -a /root/.gradle/wrapper/dists "$stage/gradle-home/wrapper/"
find "$stage/gradle-home" -type f \( -name '*.lock' -o -name '*.lck' -o -name 'gc.properties' \) -delete
sha256sum build.gradle settings.gradle gradlew gradle/wrapper/gradle-wrapper.jar \
    gradle/wrapper/gradle-wrapper.properties > "$stage/inputs.sha256"
cp "$stage/inputs.sha256" /export/inputs.sha256
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
    -C "$stage" -cf - gradle-home inputs.sha256 | gzip -n > /export/gradle-cache.tar.gz
cd /export
sha256sum gradle-cache.tar.gz > gradle-cache.tar.gz.sha256
