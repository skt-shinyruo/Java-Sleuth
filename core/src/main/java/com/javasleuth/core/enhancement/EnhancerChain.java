package com.javasleuth.core.enhancement;

import org.objectweb.asm.ClassVisitor;
import java.util.List;

public class EnhancerChain implements ClassEnhancer {
    private final List<ClassEnhancer> enhancers;

    public EnhancerChain(List<ClassEnhancer> enhancers) {
        this.enhancers = enhancers;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
        ClassVisitor visitor = delegate;
        for (ClassEnhancer enhancer : enhancers) {
            visitor = enhancer.createClassVisitor(visitor, className);
        }
        return visitor;
    }

    @Override
    public String getDescription() {
        return "Enhancer chain size=" + enhancers.size();
    }
}
