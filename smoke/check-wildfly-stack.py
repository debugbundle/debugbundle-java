#!/usr/bin/env python3
"""Check an installed WildFly agent's local event files after a redirected stack burst."""

import json
import sys
from pathlib import Path


def main(directory: Path, expected_service: str) -> None:
    events: list[dict] = []
    for path in directory.glob("*.events.json"):
        batch = json.loads(path.read_text())
        if not isinstance(batch, list):
            raise AssertionError(f"invalid event batch: {path.name}")
        events.extend(batch)

    roots = [
        event for event in events
        if event.get("event_type") == "backend_exception"
        and event.get("payload", {}).get("name") == "com.debugbundle.smoke.SyntheticSmokeException"
    ]
    if len(roots) != 1:
        raise AssertionError(f"expected one installed synthetic exception, got {len(roots)}")
    root = roots[0]
    stack = root["payload"]["stack"]
    if len(stack.splitlines()) != 94 or "Caused by: java.lang.IllegalStateException" not in stack:
        raise AssertionError("redirected root lost its bounded cause chain or application frames")
    if root["service"]["name"] != expected_service:
        raise AssertionError("agent service identity changed")
    continuations = [
        event for event in events
        if event.get("event_type") == "log_event"
        and "com.debugbundle.smoke.ChartService.render" in event.get("payload", {}).get("message", "")
    ]
    if continuations:
        raise AssertionError(f"redirected frames escaped as {len(continuations)} standalone logs")


if __name__ == "__main__":
    main(Path(sys.argv[1]), sys.argv[2])
