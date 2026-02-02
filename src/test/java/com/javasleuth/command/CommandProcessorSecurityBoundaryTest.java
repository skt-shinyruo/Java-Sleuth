package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
    public void testStartBlockedWhenHmacModeButSecretEmpty() throws Exception {
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
            Assert.assertTrue(err.contains("security.mode=hmac"));
            Assert.assertTrue(err.contains("security.hmac.secret"));
        } finally {
            config.clearRuntimeConfig();
        }
    }
}
