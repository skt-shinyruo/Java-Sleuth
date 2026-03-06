package com.javasleuth.launcher.client;

import com.javasleuth.core.command.CommandProcessor;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.foundation.config.ProductionConfig;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

public class ProtocolClientIntegrationTest {

    @Test
    public void testHandshakeAndVersionCommandOverBinary() throws Exception {
        CommandProcessor processor = new CommandProcessor(dummyInstrumentation(), new SleuthClassFileTransformer(ProductionConfig.createDefault()));
        ProductionConfig config = processor.getConfig();
        try {
            config.clearRuntimeConfig();
            config.setRuntimeConfig("server.bind.address", "127.0.0.1");
            config.setRuntimeConfig("server.port", "0");
            config.setRuntimeConfig("protocol.streaming.enabled", "true");

            Thread serverThread = new Thread(processor::start, "test-cp-start");
            serverThread.setDaemon(true);
            serverThread.start();

            int port = waitForBoundPort(processor, 3, TimeUnit.SECONDS);

            CapturingOutput output = new CapturingOutput();
            try (ProtocolClient client = ProtocolClient.connectWithRetry(
                "127.0.0.1",
                port,
                "binary",
                true,
                1024 * 1024,
                8192
            )) {
                CommandResult result = client.execute("version", false, output);
                Assert.assertTrue("Expected version command to succeed", result.isOk());
            } finally {
                processor.shutdownGracefully(3);
                serverThread.join(2000);
            }

            Assert.assertTrue("Expected stdout contains version", output.getStdout().contains("version:"));
        } finally {
            config.clearRuntimeConfig();
        }
    }

    @Test
    public void testHandshakeNegotiatesStreamingDisabledOverridesClientHint() throws Exception {
        CommandProcessor processor = new CommandProcessor(dummyInstrumentation(), new SleuthClassFileTransformer(ProductionConfig.createDefault()));
        ProductionConfig config = processor.getConfig();
        try {
            config.clearRuntimeConfig();
            config.setRuntimeConfig("server.bind.address", "127.0.0.1");
            config.setRuntimeConfig("server.port", "0");
            config.setRuntimeConfig("protocol.streaming.enabled", "false");

            Thread serverThread = new Thread(processor::start, "test-cp-start-streaming-disabled");
            serverThread.setDaemon(true);
            serverThread.start();

            int port = waitForBoundPort(processor, 3, TimeUnit.SECONDS);

            CapturingOutput output = new CapturingOutput();
            try (ProtocolClient client = ProtocolClient.connectWithRetry(
                "127.0.0.1",
                port,
                "binary",
                true,
                1024 * 1024,
                8192
            )) {
                Assert.assertFalse(
                    "Expected client streaming to be disabled when server CONFIG streaming=false",
                    client.isStreamingEnabled()
                );

                CommandResult result = client.execute("version", true, output);
                Assert.assertTrue("Expected version command to succeed", result.isOk());
            } finally {
                processor.shutdownGracefully(3);
                serverThread.join(2000);
            }

            Assert.assertTrue("Expected stdout contains version", output.getStdout().contains("version:"));
        } finally {
            config.clearRuntimeConfig();
        }
    }

    @Test
    public void testConnectWithRetry_succeedsWhenServerStartsLate() throws Exception {
        CommandProcessor processor = new CommandProcessor(dummyInstrumentation(), new SleuthClassFileTransformer(ProductionConfig.createDefault()));
        ProductionConfig config = processor.getConfig();
        try {
            config.clearRuntimeConfig();
            config.setRuntimeConfig("server.bind.address", "127.0.0.1");
            config.setRuntimeConfig("server.port", "0");
            config.setRuntimeConfig("protocol.streaming.enabled", "true");

            Thread serverThread = new Thread(() -> {
                try {
                    // Simulate attach/startup jitter: server starts a bit later.
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                processor.start();
            }, "test-cp-start-delayed");
            serverThread.setDaemon(true);
            serverThread.start();

            int port = waitForBoundPort(processor, 3, TimeUnit.SECONDS);

            CapturingOutput output = new CapturingOutput();
            try (ProtocolClient client = ProtocolClient.connectWithRetry(
                "127.0.0.1",
                port,
                "binary",
                true,
                1024 * 1024,
                8192,
                10000,
                1000,
                5000
            )) {
                CommandResult result = client.execute("version", false, output);
                Assert.assertTrue("Expected version command to succeed", result.isOk());
            } finally {
                processor.shutdownGracefully(3);
                serverThread.join(2000);
            }

            Assert.assertTrue("Expected stdout contains version", output.getStdout().contains("version:"));
        } finally {
            config.clearRuntimeConfig();
        }
    }

    private static int waitForBoundPort(CommandProcessor processor, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            ServerSocket ss = readServerSocket(processor);
            if (ss != null && !ss.isClosed()) {
                int port = ss.getLocalPort();
                if (port > 0) {
                    return port;
                }
            }
            Thread.sleep(50);
        }
        ServerSocket ss = readServerSocket(processor);
        if (ss != null && !ss.isClosed() && ss.getLocalPort() > 0) {
            return ss.getLocalPort();
        }
        throw new AssertionError("ServerSocket not open within timeout");
    }

    private static ServerSocket readServerSocket(CommandProcessor processor) throws Exception {
        Field f = CommandProcessor.class.getDeclaredField("serverSocket");
        f.setAccessible(true);
        return (ServerSocket) f.get(processor);
    }

    private static Instrumentation dummyInstrumentation() {
        return (Instrumentation)
            Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] { Instrumentation.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        Class<?> returnType = method.getReturnType();
                        if (returnType == void.class) {
                            return null;
                        }
                        if (returnType == boolean.class) {
                            return false;
                        }
                        if (returnType == int.class) {
                            return 0;
                        }
                        if (returnType == long.class) {
                            return 0L;
                        }
                        if (returnType.isArray()) {
                            return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                        }
                        return null;
                    }
                }
            );
    }

    private static final class CapturingOutput implements ProtocolOutput {
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();

        @Override
        public void onStdoutLine(String line) {
            stdout.append(line).append('\n');
        }

        @Override
        public void onStderrLine(String line) {
            stderr.append(line).append('\n');
        }

        @Override
        public void onStdoutChunk(String chunk) {
            stdout.append(chunk);
        }

        @Override
        public void onStderrChunk(String chunk) {
            stderr.append(chunk);
        }

        public String getStdout() {
            return stdout.toString();
        }

        @SuppressWarnings("unused")
        public String getStderr() {
            return stderr.toString();
        }
    }
}
