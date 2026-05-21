package com.debugbundle.sdk;

import java.util.concurrent.ThreadFactory;

final class DebugBundleThreadFactory implements ThreadFactory {
    private final String name;

    DebugBundleThreadFactory(String name) {
        this.name = name;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
