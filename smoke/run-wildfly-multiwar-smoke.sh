#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
RUN_ID=$$
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/debugbundle-java-wildfly.XXXXXX")
RUNNING_CONTAINERS=""

cleanup() {
  for container in $RUNNING_CONTAINERS; do
    docker rm -f "$container" >/dev/null 2>&1 || true
  done
  rm -rf "$TEMP_DIR"
}

trap cleanup EXIT INT TERM

wait_for_route() {
  container=$1
  route=$2
  attempt=0
  while :; do
    port=$(docker port "$container" 8080/tcp 2>/dev/null | sed -n '1s/.*://p')
    if [ -n "$port" ] && curl -fsS "http://127.0.0.1:$port$route" >/dev/null 2>&1; then
      return
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 120 ]; then
      echo "WildFly route $route did not become ready in $container" >&2
      docker logs "$container" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

verify_lane() {
  lane=$1
  dockerfile=$2
  first_route=$3
  second_route=$4
  first_service=$5
  second_service=$6
  image="debugbundle-java-${lane}:${RUN_ID}"
  container="debugbundle-java-${lane}-${RUN_ID}"

  docker build -f "$dockerfile" -t "$image" "$REPO_DIR"
  docker run -d --name "$container" -p 127.0.0.1::8080 "$image" >/dev/null
  RUNNING_CONTAINERS="$RUNNING_CONTAINERS $container"

  wait_for_route "$container" "${first_route%failure.jsp}"
  port=$(docker port "$container" 8080/tcp | sed -n '1s/.*://p')
  curl -sS "http://127.0.0.1:$port$first_route" >/dev/null
  curl -sS "http://127.0.0.1:$port$second_route" >/dev/null
  curl -fsS "http://127.0.0.1:$port${first_route%failure.jsp}stack.jsp" > "$TEMP_DIR/$lane-stack-response.txt"

  attempt=0
  while ! docker exec "$container" sh -lc 'test "$(find standalone/debugbundle-events -type f -name "*.events.json" 2>/dev/null | wc -l)" -ge 2'; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
      echo "WildFly lane $lane did not persist both deployment events" >&2
      docker logs "$container" >&2 || true
      exit 1
    fi
    sleep 1
  done

  attempt=0
  while ! docker exec "$container" sh -lc 'grep -Rqs "SyntheticSmokeException" standalone/debugbundle-events --include="*.events.json" 2>/dev/null'; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
      echo "WildFly lane $lane did not persist the assembled synthetic stack" >&2
      cat "$TEMP_DIR/$lane-stack-response.txt" >&2
      docker exec "$container" sh -lc 'find standalone/debugbundle-events -type f -maxdepth 2 -name "*.events.json" -print | head -20' >&2 || true
      docker exec "$container" sh -lc 'for file in standalone/debugbundle-events/*.events.json; do head -c 1200 "$file"; printf "\n"; done' >&2 || true
      docker logs "$container" >&2 || true
      exit 1
    fi
    sleep 1
  done

  lane_output="$TEMP_DIR/$lane"
  mkdir -p "$lane_output"
  docker cp "$container:/opt/jboss/wildfly/standalone/debugbundle-events/." "$lane_output/"

  if ! grep -Rqs "\"name\":\"$first_service\"" "$lane_output" ||
    ! grep -Rqs "\"name\":\"$second_service\"" "$lane_output" ||
    ! grep -Rqs '"event_type":"request_event"' "$lane_output"; then
    echo "WildFly lane $lane did not preserve per-deployment service identity and request capture" >&2
    find "$lane_output" -type f -maxdepth 2 -print -exec sed -n '1,120p' {} \; >&2
    exit 1
  fi
  case "$lane" in
    jakarta) agent_service=wildfly-stack-smoke ;;
    javax) agent_service=wildfly-stack-smoke ;;
  esac
  python3 "$REPO_DIR/smoke/check-wildfly-stack.py" "$lane_output" "$agent_service"

  docker rm -f "$container" >/dev/null
  RUNNING_CONTAINERS=$(printf '%s' "$RUNNING_CONTAINERS" | sed "s/ $container//")
}

verify_lane \
  jakarta \
  "$REPO_DIR/smoke/wildfly-multiwar/Dockerfile" \
  /orders/failure.jsp \
  /identity/failure.jsp \
  orders-service \
  identity-service

verify_lane \
  javax \
  "$REPO_DIR/smoke/wildfly-multiwar-javax/Dockerfile" \
  /orders-legacy/failure.jsp \
  /identity-legacy/failure.jsp \
  orders-legacy-service \
  identity-legacy-service

echo "WildFly Jakarta and Javax multi-WAR smokes passed with isolated deployment identities and javaagent startup."
