#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "Usage: bash ./smoke/run-app-driven-smoke.sh [--published <version>]" >&2
}

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
smoke_pom="$repo_root/smoke/app-driven-core/pom.xml"
local_repo="$(mktemp -d "${TMPDIR:-/tmp}/debugbundle-java-smoke.XXXXXX")"
published_version=""

cleanup() {
  rm -rf "$local_repo"
}

trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --published)
      shift
      if [[ $# -eq 0 ]]; then
        usage
        exit 1
      fi
      published_version="$1"
      shift
      ;;
    *)
      usage
      exit 1
      ;;
  esac
done

if [[ -n "$published_version" ]]; then
  version="$published_version"
else
  version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$repo_root/pom.xml" | head -n 1)"
  mvn -B -ntp \
    -Dmaven.repo.local="$local_repo" \
    -f "$repo_root/pom.xml" \
    -pl debugbundle-java-core \
    -am \
    -DskipTests \
    install
fi

mvn -B -ntp \
  -Dmaven.repo.local="$local_repo" \
  -f "$smoke_pom" \
  -Ddebugbundle.version="$version" \
  compile \
  exec:java \
  -Dexec.mainClass=com.debugbundle.smoke.AppDrivenSmoke