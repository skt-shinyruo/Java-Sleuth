package com.javasleuth.core.enhancement;

import com.javasleuth.foundation.util.WildcardMatcher;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

public final class MonitorEnhancer implements BootstrapDependentEnhancer {
    private final String targetClassName;
    private final String targetClassInternalName;
    private final String targetMethodPattern;
    private final String targetMethodDesc;
    private final String monitorId;

    public MonitorEnhancer(String className, String methodPattern, String methodDesc, String monitorId) {
        this.targetClassName = className;
        this.targetClassInternalName = className == null ? null : className.replace('.', '/');
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

    @Override
    public String requiredBootstrapClassName() {
        return "com.javasleuth.bootstrap.spy.SleuthSpyAPI";
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
            // Initialize locals used by the exception handler before tryStart to avoid VerifyError.
            startTimeVar = newLocal(Type.LONG_TYPE);
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, startTimeVar);

            mv.visitLabel(tryStart);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeVar);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                generateNormalExitCall();
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
            generateExceptionExitCall(exceptionVar);
            mv.visitVarInsn(ALOAD, exceptionVar);
            mv.visitInsn(ATHROW);
            super.visitMaxs(maxStack + 10, maxLocals + 3);
        }

        private void generateNormalExitCall() {
            mv.visitLdcInsn(monitorId);

            if (targetClassInternalName != null) {
                mv.visitLdcInsn(Type.getObjectType(targetClassInternalName));
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitLdcInsn(methodName + "|" + methodDesc);

            // target (this or null)
            if ((methodAccess & ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // args
            mv.visitInsn(ACONST_NULL);
            // returnObject
            mv.visitInsn(ACONST_NULL);
            // returnCaptured
            push(true);

            // start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // duration = nanoTime - startTimeVar
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);

            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atExit", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;ZJJ)V", false);
        }

        private void generateExceptionExitCall(int exceptionVar) {
            mv.visitLdcInsn(monitorId);

            if (targetClassInternalName != null) {
                mv.visitLdcInsn(Type.getObjectType(targetClassInternalName));
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitLdcInsn(methodName + "|" + methodDesc);

            // target (this or null)
            if ((methodAccess & ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // args
            mv.visitInsn(ACONST_NULL);

            // throwable
            mv.visitVarInsn(ALOAD, exceptionVar);

            // start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // duration = nanoTime - startTimeVar
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);

            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atExceptionExit", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Throwable;JJ)V", false);
        }
    }
}
