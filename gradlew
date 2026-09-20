#!/bin/sh
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
PROPS="$DIR/gradle/wrapper/gradle-wrapper.properties"
if [ ! -f "$PROPS" ]; then echo "missing $PROPS" >&2; exit 1; fi
DIST_URL="$(awk -F= '/^distributionUrl=/{print $2}' "$PROPS" | sed 's/^https\\:/https:/')"
DISTS="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists"
NAME="$(basename "${DIST_URL%.zip}")"
GRADLE_DIR="$DISTS/$NAME/gradle-8.7"
if [ ! -d "$GRADLE_DIR" ]; then
  echo "Downloading $DIST_URL"
  mkdir -p "$DISTS/$NAME"
  curl -L -o "$DISTS/$NAME/$NAME.zip" "$DIST_URL"
  unzip -q -o "$DISTS/$NAME/$NAME.zip" -d "$DISTS/$NAME"
fi
exec "$GRADLE_DIR/bin/gradle" "$@"
