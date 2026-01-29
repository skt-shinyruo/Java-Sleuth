package com.javasleuth.enhancement;

import com.javasleuth.util.WildcardMatcher;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * StackEnhancer 用于在目标方法入口处采集调用栈（简化版）。
 *
 * <p>仅插入 onMethodEnter，避免对返回值/异常路径产生额外影响。</p>
 */
public final class StackEnhancer implements ClassEnhancer {
    private final String targetClassName;
    private final String targetMethodPattern;
    private final String targetMethodDesc;
    private final String stackId;
    private final int maxDepth;

    public StackEnhancer(String className, String methodPattern, String methodDesc, String stackId, int maxDepth) {
        this.targetClassName = className;
        this.targetMethodPattern = methodPattern;
        this.targetMethodDesc = methodDesc;
        this.stackId = stackId;
        this.maxDepth = maxDepth;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
        return new StackClassVisitor(delegate);
    }

    @Override
    public String getDescription() {
        return "Stack enhancer for " + targetClassName + "." + targetMethodPattern;
    }

    private final class StackClassVisitor extends ClassVisitor {
        private StackClassVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (shouldEnhanceMethod(name, descriptor)) {
                return new StackMethodVisitor(mv, access, name, descriptor);
            }
            return mv;
        }

        private boolean shouldEnhanceMethod(String methodName, String methodDesc) {
            boolean nameMatches = WildcardMatcher.matches(methodName, targetMethodPattern);
            if (!nameMatches) {
                return false;
            }
            if (targetMethodDesc == null || "*".equals(targetMethodDesc)) {
                return true;
            }
            return targetMethodDesc.equals(methodDesc);
        }
    }

    private final class StackMethodVisitor extends AdviceAdapter {
        private final String methodName;
        private final String methodDesc;

        private StackMethodVisitor(MethodVisitor mv, int access, String methodName, String methodDesc) {
            super(Opcodes.ASM9, mv, access, methodName, methodDesc);
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        protected void onMethodEnter() {
            // stackId
            mv.visitLdcInsn(stackId);
            // className
            mv.visitLdcInsn(targetClassName);
            // methodName
            mv.visitLdcInsn(methodName);
            // methodDesc
            mv.visitLdcInsn(methodDesc);
            // maxDepth
            push(maxDepth);

            mv.visitMethodInsn(INVOKESTATIC,
                "com/javasleuth/monitor/StackInterceptor",
                "onMethodEnter",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
                false
            );
        }
    }
}

