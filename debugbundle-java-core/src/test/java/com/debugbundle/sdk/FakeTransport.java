package com.debugbundle.sdk;

import java.util.ArrayList;
import java.util.List;

final class FakeTransport implements DebugBundleTransport {
    private final List<TransportResponse> responses;
    private final List<EventBatchRequest> calls = new ArrayList<>();

    FakeTransport() {
        this(List.of(new TransportResponse(202, null)));
    }

    FakeTransport(List<TransportResponse> responses) {
        this.responses = new ArrayList<>(responses);
    }

    @Override
    public TransportResponse send(EventBatchRequest request) {
        calls.add(request);
        if (responses.size() == 1) {
            return responses.get(0);
        }
        return responses.remove(0);
    }

    List<EventBatchRequest> calls() {
        return calls;
    }
}

