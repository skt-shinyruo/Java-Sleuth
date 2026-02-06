package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Test;

public class CommandProcessorSecurityBoundaryTest {

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

    @Test
    public void testStartBlockedWhenNonLoopbackBindAndSecurityOff() throws Exception {
        CommandProcessor processor = new CommandProcessor(dummyInstrumentation(), new SleuthClassFileTransformer());
        ProductionConfig config = processor.getConfig();
        config.clearRuntimeConfig();
        try {
            config.setRuntimeConfig("server.bind.address", "0.0.0.0");
            config.setRuntimeConfig("server.port", "0");
            config.setRuntimeConfig("security.mode", "off");
            config.setRuntimeConfig("security.hmac.secret", "");

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            try {
                System.setOut(new PrintStream(outBuf));
                System.setErr(new PrintStream(errBuf));
                processor.start();
            } finally {
                System.setOut(oldOut);
                System.setErr(oldErr);
            }

            String status = processor.getShutdownStatus();
            Assert.assertTrue(status.contains("Server Running: NO"));
            Assert.assertTrue(status.contains("Server Socket Open: NO"));

            String out = outBuf.toString("UTF-8");
            Assert.assertFalse(out.contains("Java-Sleuth listening"));

            String err = errBuf.toString("UTF-8");
            Assert.assertTrue(err.contains("SECURITY ERROR"));
        } finally {
            config.clearRuntimeConfig();
        }
    }

    @Test
    public void testLoopbackAutogeneratesSecretWhenHmacModeButSecretEmpty() throws Exception {
        CommandProcessor processor = new CommandProcessor(dummyInstrumentation(), new SleuthClassFileTransformer());
        ProductionConfig config = processor.getConfig();
        config.clearRuntimeConfig();
        try {
            config.setRuntimeConfig("server.bind.address", "127.0.0.1");
            config.setRuntimeConfig("server.port", "0");
            config.setRuntimeConfig("security.mode", "hmac");
            config.setRuntimeConfig("security.hmac.secret", "");

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            PrintStream oldOut = System.out;
            PrintStream oldErr = System.err;
            Thread serverThread = null;
            try {
                System.setOut(new PrintStream(outBuf));
                System.setErr(new PrintStream(errBuf));
                serverThread = new Thread(processor::start, "test-cp-start");
                serverThread.setDaemon(true);
                serverThread.start();

                Assert.assertTrue(waitForServerSocketOpen(processor, 2, TimeUnit.SECONDS));

                String secret = config.getSecurityHmacSecret();
                Assert.assertNotNull(secret);
                Assert.assertFalse(secret.trim().isEmpty());
            } finally {
                stopServerSocketOnly(processor);
                if (serverThread != null) {
                    serverThread.join(2000);
                }
                System.setOut(oldOut);
                System.setErr(oldErr);
            }

            String err = errBuf.toString("UTF-8");
            Assert.assertFalse(err.contains("SECURITY ERROR"));
        } finally {
            config.clearRuntimeConfig();
        }
    }

    private static boolean waitForServerSocketOpen(CommandProcessor processor, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            if (isServerSocketOpen(processor)) {
                return true;
            }
            Thread.sleep(50);
        }
        return isServerSocketOpen(processor);
    }

    private static boolean isServerSocketOpen(CommandProcessor processor) throws Exception {
        Field f = CommandProcessor.class.getDeclaredField("serverSocket");
        f.setAccessible(true);
        ServerSocket ss = (ServerSocket) f.get(processor);
        return ss != null && !ss.isClosed();
    }

    private static void stopServerSocketOnly(CommandProcessor processor) {
        try {
            Field runningField = CommandProcessor.class.getDeclaredField("running");
            runningField.setAccessible(true);
            AtomicBoolean running = (AtomicBoolean) runningField.get(processor);
            if (running != null) {
                running.set(false);
            }
        } catch (Exception ignore) {
            // ignore
        }

        try {
            Field f = CommandProcessor.class.getDeclaredField("serverSocket");
            f.setAccessible(true);
            ServerSocket ss = (ServerSocket) f.get(processor);
            if (ss != null) {
                ss.close();
            }
        } catch (Exception ignore) {
            // ignore
        }
    }
}
