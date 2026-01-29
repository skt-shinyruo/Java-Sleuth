package com.javasleuth.enhancement;

import com.javasleuth.util.WildcardMatcher;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

public final class MonitorEnhancer implements ClassEnhancer {
    private final String targetClassName;
    private final String targetMethodPattern;
    private final String targetMethodDesc;
    private final String monitorId;

    public MonitorEnhancer(String className, String methodPattern, String methodDesc, String monitorId) {
        this.targetClassName = className;
        this.targetMethodPattern = methodPattern;
        this.targetMethodDesc = methodDesc;
        this.monitorId = monitorId;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
        return new MonitorClassVisitor(delegate, className);
    }

    @Override
    public String getDescription() {
        return "Monitor enhancer for " + targetClassName + "." + targetMethodPattern;
    }

    private final class MonitorClassVisitor extends ClassVisitor {
        private final String className;

        private MonitorClassVisitor(ClassVisitor delegate, String className) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (shouldEnhanceMethod(name, descriptor)) {
                return new MonitorMethodVisitor(mv, access, name, descriptor);
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

    private final class MonitorMethodVisitor extends AdviceAdapter {
        private final String methodName;
        private final String methodDesc;
        private int startTimeVar;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();

        private MonitorMethodVisitor(MethodVisitor mv, int access, String methodName, String methodDesc) {
            super(Opcodes.ASM9, mv, access, methodName, methodDesc);
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        protected void onMethodEnter() {
            mv.visitLabel(tryStart);
            startTimeVar = newLocal(Type.LONG_TYPE);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeVar);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                generateExitCall(false);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Add try-catch for exception path.
            Label handlerLabel = new Label();

            mv.visitTryCatchBlock(tryStart, tryEnd, handlerLabel, "java/lang/Throwable");
            mv.visitLabel(tryEnd);
            mv.visitLabel(handlerLabel);
            int exceptionVar = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exceptionVar);
            generateExitCall(true);
            mv.visitVarInsn(ALOAD, exceptionVar);
            mv.visitInsn(ATHROW);
            super.visitMaxs(maxStack + 10, maxLocals + 3);
        }

        private void generateExitCall(boolean isException) {
            mv.visitLdcInsn(monitorId);
            mv.visitLdcInsn(targetClassName);
            mv.visitLdcInsn(methodName);
            mv.visitLdcInsn(methodDesc);

            // duration = nanoTime - startTimeVar
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);

            push(isException);

            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/monitor/MonitorInterceptor",
                "onMethodExit", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JZ)V", false);
        }
    }
}
