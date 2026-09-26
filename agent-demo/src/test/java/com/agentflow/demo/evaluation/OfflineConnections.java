package com.agentflow.demo.evaluation;

import java.net.URI;

/** Offline drivers only accept literal loopback hosts and never follow redirects. */
final class OfflineConnections {
    private OfflineConnections() { }
    static URI requireLoopback(URI uri) {
        if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null
                || !java.util.Set.of("127.0.0.1", "[::1]").contains(uri.getHost()))
            throw new IllegalArgumentException("OFFLINE_LOOPBACK_REQUIRED");
        return uri;
    }
}
