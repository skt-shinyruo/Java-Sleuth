package com.javasleuth.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;

public final class ClassBytecodeResolver {
    public enum Source {
        CURRENT_RETRANSFORM,
        RESOURCE
    }

    public static final class Result {
        private final byte[] bytes;
        private final Source source;
        private final Throwable currentFailure;

        private Result(byte[] bytes, Source source, Throwable currentFailure) {
            this.bytes = bytes;
            this.source = source;
            this.currentFailure = currentFailure;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public Source getSource() {
            return source;
        }

        public Throwable getCurrentFailure() {
            return currentFailure;
        }

        public boolean isCurrentJvmBytecode() {
            return source == Source.CURRENT_RETRANSFORM;
        }

        public String sourceLabel() {
            if (source == Source.CURRENT_RETRANSFORM) {
                return "current JVM bytecode";
            }
            if (source == Source.RESOURCE) {
                return "classpath resource fallback";
            }
            return "unavailable";
        }
    }

    private ClassBytecodeResolver() {}

    public static Result resolve(Instrumentation instrumentation, Class<?> clazz) {
        Capture capture = captureCurrentBytes(instrumentation, clazz);
        if (capture.bytes != null) {
            return new Result(capture.bytes, Source.CURRENT_RETRANSFORM, null);
        }
        byte[] resource = readResourceBytes(clazz);
        if (resource != null) {
            return new Result(resource, Source.RESOURCE, capture.failure);
        }
        return new Result(null, null, capture.failure);
    }

    private static Capture captureCurrentBytes(Instrumentation instrumentation, Class<?> clazz) {
        if (instrumentation == null || clazz == null) {
            return Capture.empty(null);
        }
        try {
            if (!instrumentation.isRetransformClassesSupported() || !instrumentation.isModifiableClass(clazz)) {
                return Capture.empty(null);
            }
        } catch (Throwable t) {
            return Capture.empty(t);
        }

        final AtomicReference<byte[]> captured = new AtomicReference<byte[]>();
        final String targetInternalName = clazz.getName().replace('.', '/');
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
            ) throws IllegalClassFormatException {
                if (classfileBuffer == null) {
                    return null;
                }
                boolean sameClass = classBeingRedefined == clazz
                    || (classBeingRedefined == null && targetInternalName.equals(className));
                if (!sameClass) {
                    return null;
                }
                byte[] copy = new byte[classfileBuffer.length];
                System.arraycopy(classfileBuffer, 0, copy, 0, classfileBuffer.length);
                captured.compareAndSet(null, copy);
                return null;
            }
        };

        boolean added = false;
        Throwable failure = null;
        try {
            instrumentation.addTransformer(transformer, true);
            added = true;
            instrumentation.retransformClasses(clazz);
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (added) {
                try {
                    instrumentation.removeTransformer(transformer);
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
        }
        return new Capture(captured.get(), failure);
    }

    private static byte[] readResourceBytes(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        ClassLoader loader = clazz.getClassLoader();
        InputStream in = loader != null
            ? loader.getResourceAsStream(resourceName)
            : ClassLoader.getSystemResourceAsStream(resourceName);
        if (in == null) {
            return null;
        }
        try {
            return readFully(in);
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }

    private static final class Capture {
        private final byte[] bytes;
        private final Throwable failure;

        private Capture(byte[] bytes, Throwable failure) {
            this.bytes = bytes;
            this.failure = failure;
        }

        private static Capture empty(Throwable failure) {
            return new Capture(null, failure);
        }
    }
}
