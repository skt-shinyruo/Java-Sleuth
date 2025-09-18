package com.javasleuth.enhancement;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

public class TraceEnhancer implements ClassEnhancer {
    private final String targetClassName;
    private final String targetMethodName;
    private final String targetMethodDesc;
    private final String traceId;

    public TraceEnhancer(String className, String methodName, String methodDesc, String traceId) {
        this.targetClassName = className;
        this.targetMethodName = methodName;
        this.targetMethodDesc = methodDesc;
        this.traceId = traceId;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
        return new TraceClassVisitor(delegate, className);
    }

    @Override
    public String getDescription() {
        return "Trace enhancer for " + targetClassName + "." + targetMethodName;
    }

    private class TraceClassVisitor extends ClassVisitor {
        private final String className;

        public TraceClassVisitor(ClassVisitor delegate, String className) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (shouldEnhanceMethod(name, descriptor)) {
                return new TraceMethodVisitor(mv, access, name, descriptor);
            }

            return mv;
        }

        private boolean shouldEnhanceMethod(String methodName, String methodDesc) {
            if ("*".equals(targetMethodName)) {
                return true;
            }
            if (targetMethodDesc == null || "*".equals(targetMethodDesc)) {
                return targetMethodName.equals(methodName);
            }
            return targetMethodName.equals(methodName) && targetMethodDesc.equals(methodDesc);
        }
    }

    private class TraceMethodVisitor extends AdviceAdapter {
        private final String methodName;
        private final String methodDesc;
        private int startTimeVar;

        public TraceMethodVisitor(MethodVisitor mv, int access, String methodName, String methodDesc) {
            super(Opcodes.ASM9, mv, access, methodName, methodDesc);
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        protected void onMethodEnter() {
            // Store start time
            startTimeVar = newLocal(Type.LONG_TYPE);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeVar);

            // Call trace interceptor for method entry
            generateTraceEntryCall();
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                generateTraceExitCall(false);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // Trace method calls within the target method
            if (opcode == INVOKEVIRTUAL || opcode == INVOKESPECIAL || opcode == INVOKESTATIC || opcode == INVOKEINTERFACE) {
                generateSubMethodCall(owner, name, descriptor);
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Add try-catch for exception tracing
            Label startLabel = new Label();
            Label endLabel = new Label();
            Label handlerLabel = new Label();

            mv.visitTryCatchBlock(startLabel, endLabel, handlerLabel, "java/lang/Throwable");

            mv.visitLabel(startLabel);
            super.visitMaxs(maxStack + 10, maxLocals + 3);
            mv.visitLabel(endLabel);

            mv.visitLabel(handlerLabel);
            int exceptionVar = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exceptionVar);
            generateTraceExitCall(true);
            mv.visitVarInsn(ALOAD, exceptionVar);
            mv.visitInsn(ATHROW);
        }

        private void generateTraceEntryCall() {
            // Push trace ID
            mv.visitLdcInsn(traceId);

            // Push class name
            mv.visitLdcInsn(targetClassName);

            // Push method name
            mv.visitLdcInsn(methodName);

            // Push method descriptor
            mv.visitLdcInsn(methodDesc);

            // Push start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // Call TraceInterceptor.onMethodEntry
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/monitor/TraceInterceptor",
                "onMethodEntry", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V", false);
        }

        private void generateTraceExitCall(boolean isException) {
            // Push trace ID
            mv.visitLdcInsn(traceId);

            // Push class name
            mv.visitLdcInsn(targetClassName);

            // Push method name
            mv.visitLdcInsn(methodName);

            // Push method descriptor
            mv.visitLdcInsn(methodDesc);

            // Push start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // Calculate duration
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);

            // Push exception flag
            push(isException);

            // Call TraceInterceptor.onMethodExit
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/monitor/TraceInterceptor",
                "onMethodExit", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJZ)V", false);
        }

        private void generateSubMethodCall(String owner, String name, String descriptor) {
            // Don't trace calls to system classes or our own classes
            if (owner.startsWith("java/") || owner.startsWith("javax/") ||
                owner.startsWith("sun/") || owner.startsWith("com/javasleuth/")) {
                return;
            }

            // Push trace ID
            mv.visitLdcInsn(traceId);

            // Push target class
            mv.visitLdcInsn(owner.replace('/', '.'));

            // Push method name
            mv.visitLdcInsn(name);

            // Push method descriptor
            mv.visitLdcInsn(descriptor);

            // Push current time
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);

            // Call TraceInterceptor.onSubMethodCall
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/monitor/TraceInterceptor",
                "onSubMethodCall", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V", false);
        }
    }
}