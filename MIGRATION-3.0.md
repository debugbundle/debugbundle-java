# Java 3.0 migration

Java 3.0 changes capture and hook timing. All Java modules must use the same version. Keep a pinned 2.x installation available during rollout.

## Capture and hook ordering

In 2.x, `beforeSend` ran synchronously during capture before local capture policy, sampling, and buffering. In 3.0, the SDK checks the effective log level and metadata-only policy first, then performs bounded admission and privacy protection. Accepted events are passed to `beforeSend` on the single background delivery worker after sampling and duplicate suppression, just before transport. A filtered INFO log never invokes the hook. A hook can still return a validated replacement or `null` to drop an admitted event; invalid results and exceptions keep the safe original. The hook must not rely on caller thread-local state or mutate application state that must be visible before `capture*` returns. Put correlation and request values in the capture context instead.

Transport, including local file delivery, runs on the background worker. Reaching `batchSize` only schedules it. `flush()` returns a `CompletableFuture` for explicit lifecycle drains; calling `join()` on a request thread intentionally waits and defeats the nonblocking capture guarantee. Use it only during controlled teardown or tests.

The in-memory delivery budget is at most 1,000 events and 8 MiB, with at most 64 concurrent capture admissions. An exception, ERROR, or request failure eligible to open an incident can displace a lower-priority event. Once all retained events are high-priority, further incidents can also be dropped. The SDK emits at most one aggregate queue-pressure record per 60 seconds when delivery is available; a failed or full transport may delay that record. Treat this as lossy diagnostics, not a lossless audit log.

Concurrent explicit `flush()` requests coalesce into bounded worker passes and retain at most 64 pending completion waiters. A request arriving during an active pass waits for the next pass. Excess callers receive an already-completed future while the worker is saturated; a completed future is not a delivery acknowledgement. Cancelled timed flush wakeups are removed from the worker queue, so a held sender cannot accumulate scheduled tasks. Use `status()` and ingestion evidence when delivery confirmation matters.

## Exception snapshots and ownership

`captureException` preserves a sanitized event shell, the capture timestamp, the caller thread ID, request context, and breadcrumbs before returning. It attaches a weak reference to the exception outside the telemetry map. A coalesced task on the existing single delivery worker resolves that reference promptly, reads the original message, throw-site stack and bounded cause chain, and protects the result before replacing the retained shell. Runtime hostname/JVM facts are also gathered on that worker. Custom exception getters and an application-held `Throwable` monitor cannot block the capture caller.

There are at most 1,000 queued weak handles, within the same 1,000-event / 8 MiB sanitized-event budget. A weak handle does not prolong the exception's lifetime. The worker temporarily holds at most one root exception graph while inspecting it; the size of an arbitrary application exception graph is not byte-bounded. Raw exceptions are never placed in telemetry maps, persisted, or passed to `beforeSend`. Protected snapshot and hook replacements are charged incrementally before the next callback runs; an over-budget valid replacement is dropped without restoring pre-hook content. Retry reuses the finalized protected event even when a hook changes its event ID.

Exception inspection is best effort. If an input is collected before the worker resolves its weak handle, the event retains its safe metadata and explicitly reports `[exception details unavailable: input no longer reachable]` in the stack. Metadata may reflect application changes made after capture and before inspection. Keep required application behavior out of exception accessors. A getter or monitor that never returns can stall this one daemon worker and prevent diagnostic delivery; the SDK creates no replacement worker and capture remains bounded and lossy under pressure. `close()` stops admission, clears pending events/handles, requests worker interruption, and completes waiting flush futures without waiting for the accessor. Java cannot forcibly terminate a getter that ignores interruption, so that one active processing frame, its bounded batch metadata/weak handles, and its current exception graph can remain until it returns.

## INFO and exception context

The default `WARNING` threshold rejects INFO before event construction and privacy scanning. For request-scoped INFO breadcrumbs, opt in with `DebugBundleConfig.builder().infoBreadcrumbs(true)`. The SDK samples burst entries into a 20-entry, 16 KiB, 60-second sanitized ring and attaches retained entries to a captured exception as `probe_data.items` with label `log.info`. It does not upload those entries independently when INFO is below the effective threshold. Explicit standalone INFO remains available by setting the log level and server capture policy to allow it. A server policy that disables breadcrumb capture takes precedence.

The level and capture policy also run before checking log-message contents. When an admitted message exceeds 16 KiB, capture uses the existing `[REDACTED]` oversized-string result without scanning the full application string; a full queue rejects the message before privacy or event construction.

JUL records with an attached `Throwable` become one structured `backend_exception` with a bounded cause chain and frames. Redirected stderr root, `Caused by`, `at ...`, `Suppressed`, and `... N more` records are assembled by client, thread and exact logger name into a bounded incident. A delayed or malformed trace can lose context rather than blocking the host. Multiple distinct roots on the same thread remain separate incidents. Logger names over 256 characters fall back to the ordinary root log and a client-scoped aggregate for continuation lines, avoiding unbounded retained logger keys or cross-project counts.

If an application installs JUL or uncaught handlers through `DebugBundle`, call `DebugBundle.shutdown()` on application stop or WAR undeploy to detach those hooks. Servlet listeners own their per-WAR client lifecycle independently. WildFly/JBoss installs should attach JUL only after its LogManager has initialized, using `DebugBundle.captureJavaUtilLogging(Logger.getLogger("stderr"))` for redirected stderr. Enabling JUL in an early JVM `premain` can initialize the wrong LogManager and prevent WildFly startup.

## Upgrade checklist

1. Review `beforeSend` for caller-thread assumptions, synchronous side effects, and reliance on a filtered record. Move required application behavior outside the SDK hook; keep the hook focused on final event policy.
2. Keep every Java module on the same 3.0 version. Do not mix 2.x core with 3.x adapters, starter, or agent.
3. Remove `flush().join()` from request and logging paths. Drain only during controlled shutdown if needed.
4. Decide whether to enable request-scoped INFO breadcrumbs or intentional standalone INFO capture. Default filtering remains `WARNING`.
5. Review custom exception accessors for deferred execution and the explicit weak-handle fallback; do not depend on exception getter side effects occurring before capture returns.
6. Verify installed JUL, Logback, servlet/JAX-RS, and WildFly paths with your privacy policy and transport settings before promoting the version.

No wire-event schema migration is needed for Java 3.0; server ingestion continues to accept existing 2.x events. The server-side Java stderr continuation classifier improves older installs too, but it cannot restore complete cause chains from already fragmented historical events.
