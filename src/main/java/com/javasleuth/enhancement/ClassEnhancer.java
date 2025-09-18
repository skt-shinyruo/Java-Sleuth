package com.javasleuth.enhancement;

import org.objectweb.asm.ClassVisitor;

public interface ClassEnhancer {
    ClassVisitor createClassVisitor(ClassVisitor delegate, String className);
    String getDescription();
}