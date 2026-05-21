package com.debugbundle.sdk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

final class FakeRemoteConfigFetcher implements RemoteConfigFetcher {
    private final Queue<RemoteConfigResponse> responses = new ArrayDeque<>();
    private final List<RemoteConfigRequest> requests = new ArrayList<>();

    FakeRemoteConfigFetcher(RemoteConfigResponse... responses) {
        for (RemoteConfigResponse response : responses) {
            this.responses.add(response);
        }
    }

    @Override
    public RemoteConfigResponse fetch(RemoteConfigRequest request) {
        requests.add(request);
        if (responses.isEmpty()) {
            return new RemoteConfigResponse(500, null, null);
        }
        return responses.remove();
    }

    List<RemoteConfigRequest> requests() {
        return requests;
    }
}
