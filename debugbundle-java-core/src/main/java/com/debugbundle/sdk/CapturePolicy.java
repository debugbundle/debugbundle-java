package com.debugbundle.sdk;

import java.util.List;

record CapturePolicy(
        String preset,
        CaptureLogsMode captureLogs,
        CaptureRequestEventsMode captureRequestEvents,
        CaptureBreadcrumbsMode captureBreadcrumbs,
        CaptureProbeEventsMode captureProbeEvents,
        List<Integer> immediateClientErrorStatuses
) {
    static final CapturePolicy BALANCED = new CapturePolicy(
            "balanced",
            CaptureLogsMode.WARNING,
            CaptureRequestEventsMode.FAILURES_ONLY,
            CaptureBreadcrumbsMode.EXCEPTION_ONLY,
            CaptureProbeEventsMode.BUFFER_ONLY,
            List.of()
    );

    static final CapturePolicy MINIMAL = new CapturePolicy(
            "minimal",
            CaptureLogsMode.ERROR,
            CaptureRequestEventsMode.FAILURES_ONLY,
            CaptureBreadcrumbsMode.LOCAL_ONLY,
            CaptureProbeEventsMode.BUFFER_ONLY,
            List.of()
    );

    CapturePolicy {
        immediateClientErrorStatuses = List.copyOf(immediateClientErrorStatuses);
    }

    enum CaptureLogsMode {
        OFF,
        ERROR,
        WARNING,
        INFO
    }

    enum CaptureRequestEventsMode {
        OFF,
        FAILURES_ONLY,
        FILTERED,
        ALL
    }

    enum CaptureBreadcrumbsMode {
        LOCAL_ONLY,
        EXCEPTION_ONLY,
        STANDALONE
    }

    enum CaptureProbeEventsMode {
        BUFFER_ONLY,
        STANDALONE_WHEN_ACTIVATED
    }
}
