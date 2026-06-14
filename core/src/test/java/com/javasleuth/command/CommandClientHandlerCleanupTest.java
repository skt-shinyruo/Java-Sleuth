package com.javasleuth.command;

import com.javasleuth.core.command.server.CommandClientHandler;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.core.command.session.ClientSessionIndex;
import com.javasleuth.core.command.session.ClientSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.Test;

public class CommandClientHandlerCleanupTest {

    @Test
    public void cleanupContinuesWhenSocketCloseFails() throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        config.setRuntimeConfig("security.anonymous.viewer", "true");

        MetricsCollector metricsCollector = new MetricsCollector(config);
        AuditLogger auditLogger = new AuditLogger(config);
        AuthenticationManager authenticationManager = new AuthenticationManager(config, auditLogger);
        ClientSessionIndex sessionIndex = new ClientSessionIndex();
        TrackingClientSessionRegistry clientSessionRegistry = new TrackingClientSessionRegistry();

        try {
            metricsCollector.recordClientConnection();

            CommandClientHandler handler = new CommandClientHandler(
                new AtomicBoolean(true),
                new AtomicLong(0),
                metricsCollector,
                config,
                auditLogger,
                authenticationManager,
                null,
                null,
                sessionIndex,
                clientSessionRegistry
            );

            handler.handle(new CloseFailingSocket());

            Assert.assertEquals(0, metricsCollector.getActiveConnections());
            Assert.assertEquals(0, metricsCollector.getActiveSessions());
            Assert.assertNull(sessionIndex.get("client-1"));
            Assert.assertEquals(0, authenticationManager.getActiveSessionCount());
            Assert.assertTrue(clientSessionRegistry.cleanupRan.get());
            Assert.assertNull(clientSessionRegistry.get("client-1"));
        } finally {
            clientSessionRegistry.shutdown("test_cleanup");
            authenticationManager.close();
            auditLogger.close();
            metricsCollector.shutdown();
            config.clearRuntimeConfig();
        }
    }

    private static final class TrackingClientSessionRegistry extends ClientSessionRegistry {
        private final AtomicBoolean cleanupRan = new AtomicBoolean(false);

        @Override
        public ClientSession open(String clientId, String clientInfo, String sessionId) {
            ClientSession session = super.open(clientId, clientInfo, sessionId);
            session.registerCleanup("test-cleanup", () -> cleanupRan.set(true));
            return session;
        }
    }

    private static final class CloseFailingSocket extends Socket {
        private final InputStream in = new ByteArrayInputStream(new byte[0]);
        private final OutputStream out = new ByteArrayOutputStream();
        private final SocketAddress remote = new InetSocketAddress("127.0.0.1", 23456);

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return remote;
        }

        @Override
        public synchronized void close() throws IOException {
            throw new IOException("simulated close failure");
        }
    }
}
