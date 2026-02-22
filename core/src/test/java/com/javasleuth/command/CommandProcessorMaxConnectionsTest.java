package com.javasleuth.core.command;

import com.javasleuth.foundation.command.protocol.Utf8LineCodec;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import java.io.BufferedInputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

public class CommandProcessorMaxConnectionsTest {

    @Test
    public void testRejectsNewConnectionsWhenMaxConnectionsReached() throws Exception {
        ProductionConfig config = ProductionConfig.getInstance();
        config.clearRuntimeConfig();
        try {
            config.setRuntimeConfig("server.bind.address", "127.0.0.1");
            config.setRuntimeConfig("server.port", "0");
            config.setRuntimeConfig("security.mode", "off");
            config.setRuntimeConfig("server.max.connections", "1");

            CommandProcessor processor = new CommandProcessor(fakeInstrumentation(), new SleuthClassFileTransformer(config));
            Thread serverThread = new Thread(processor::start, "test-cp-start-maxconn");
            serverThread.setDaemon(true);
            serverThread.start();

            int port = waitForBoundPort(processor, 3, TimeUnit.SECONDS);

            Socket s1 = null;
            Socket s2 = null;
            try {
                s1 = new Socket("127.0.0.1", port);
                // Give the accept loop a brief moment to record the first connection.
                Thread.sleep(100);

                s2 = new Socket("127.0.0.1", port);
                BufferedInputStream in2 = new BufferedInputStream(s2.getInputStream());
                String line = Utf8LineCodec.readLine(in2, 8192);
                Assert.assertNotNull(line);
                Assert.assertTrue("Expected rejection message", line.toLowerCase().contains("too many connections"));
            } finally {
                if (s2 != null) {
                    try {
                        s2.close();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
                if (s1 != null) {
                    try {
                        s1.close();
                    } catch (Exception ignore) {
                        // ignore
                    }
                }
                processor.shutdownGracefully(2);
                serverThread.join(2000);
            }
        } finally {
            config.clearRuntimeConfig();
        }
    }

    private static int waitForBoundPort(CommandProcessor processor, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            ServerSocket ss = readServerSocket(processor);
            if (ss != null && !ss.isClosed() && ss.getLocalPort() > 0) {
                return ss.getLocalPort();
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

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, args) -> {
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            });
    }
}
