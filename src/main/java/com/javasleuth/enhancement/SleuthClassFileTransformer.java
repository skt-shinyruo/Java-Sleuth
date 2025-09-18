package com.javasleuth.enhancement;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SleuthClassFileTransformer implements ClassFileTransformer {
    private final ConcurrentHashMap<String, ClassEnhancer> enhancers = new ConcurrentHashMap<>();
    private final AtomicLong transformationCount = new AtomicLong(0);

    public void addEnhancer(String className, ClassEnhancer enhancer) {
        enhancers.put(className, enhancer);
    }

    public void removeEnhancer(String className) {
        enhancers.remove(className);
    }

    public void removeAllEnhancers() {
        enhancers.clear();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                          ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {

        if (className == null || shouldSkipClass(className)) {
            return null;
        }

        String normalizedClassName = className.replace('/', '.');
        ClassEnhancer enhancer = enhancers.get(normalizedClassName);

        if (enhancer == null) {
            return null;
        }

        try {
            transformationCount.incrementAndGet();

            ClassReader classReader = new ClassReader(classfileBuffer);
            ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

            ClassVisitor enhancedVisitor = enhancer.createClassVisitor(classWriter, normalizedClassName);
            classReader.accept(enhancedVisitor, ClassReader.EXPAND_FRAMES);

            byte[] transformedBytes = classWriter.toByteArray();

            System.out.println("Java-Sleuth: Enhanced class " + normalizedClassName +
                             " (transformation #" + transformationCount.get() + ")");

            return transformedBytes;

        } catch (Exception e) {
            System.err.println("Java-Sleuth: Failed to enhance class " + normalizedClassName + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private boolean shouldSkipClass(String className) {
        return className.startsWith("java/") ||
               className.startsWith("javax/") ||
               className.startsWith("sun/") ||
               className.startsWith("com/sun/") ||
               className.startsWith("org/objectweb/asm/") ||
               className.startsWith("com/javasleuth/") ||
               className.contains("$$");
    }

    public long getTransformationCount() {
        return transformationCount.get();
    }

    public int getActiveEnhancersCount() {
        return enhancers.size();
    }
}