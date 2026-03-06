package com.javasleuth.core.enhancement;

import com.javasleuth.foundation.util.WildcardMatcher;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * TimeTunnel-lite enhancer: record params/return/exception with cost.
 */
public final class TtEnhancer implements BootstrapDependentEnhancer {
    private final String targetClassName;
    private final String targetClassInternalName;
    private final String targetMethodPattern;
    private final String targetMethodDesc;
    private final String ttId;

    public TtEnhancer(String className, String methodPattern, String methodDesc, String ttId) {
        this.targetClassName = className;
        this.targetClassInternalName = className == null ? null : className.replace('.', '/');
        this.targetMethodPattern = methodPattern;
        this.targetMethodDesc = methodDesc;
        this.ttId = ttId;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
        return new TtClassVisitor(delegate);
    }

    @Override
    public String getDescription() {
        return "TT enhancer for " + targetClassName + "." + targetMethodPattern;
    }

    @Override
    public String requiredBootstrapClassName() {
        return "com.javasleuth.bootstrap.spy.SleuthSpyAPI";
    }

    private final class TtClassVisitor extends ClassVisitor {
        private TtClassVisitor(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (shouldEnhanceMethod(name, descriptor)) {
                return new TtMethodVisitor(mv, access, name, descriptor);
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

    private final class TtMethodVisitor extends AdviceAdapter {
        private final String methodName;
        private final String methodDesc;
        private int startTimeVar;
        private int paramsVar;
        private int exceptionVar;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();

        private TtMethodVisitor(MethodVisitor mv, int access, String methodName, String methodDesc) {
            super(Opcodes.ASM9, mv, access, methodName, methodDesc);
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        protected void onMethodEnter() {
            startTimeVar = newLocal(Type.LONG_TYPE);
            // 先初始化本地变量，避免异常处理器看到未初始化的 top 类型导致 VerifyError
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, startTimeVar);

            paramsVar = newLocal(Type.getType(Object[].class));
            push(0);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            mv.visitVarInsn(ASTORE, paramsVar);

            mv.visitLabel(tryStart);

            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeVar);

            // Capture params into local Object[]
            Type[] argumentTypes = Type.getArgumentTypes(methodDesc);
            push(argumentTypes.length);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

            int paramIndex = (methodAccess & ACC_STATIC) == 0 ? 1 : 0;
            for (int i = 0; i < argumentTypes.length; i++) {
                mv.visitInsn(DUP);
                push(i);
                Type argType = argumentTypes[i];
                mv.visitVarInsn(argType.getOpcode(ILOAD), paramIndex);
                if (argType.getSort() != Type.OBJECT && argType.getSort() != Type.ARRAY) {
                    box(argType);
                }
                mv.visitInsn(AASTORE);
                paramIndex += argType.getSize();
            }
            mv.visitVarInsn(ASTORE, paramsVar);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                Type returnType = Type.getReturnType(methodDesc);
                int returnVar = -1;
                if (returnType.getSort() != Type.VOID) {
                    returnVar = newLocal(returnType);
                    mv.visitVarInsn(returnType.getOpcode(ISTORE), returnVar);
                }
                generateExitCall(false, returnType, returnVar);
                if (returnType.getSort() != Type.VOID) {
                    mv.visitVarInsn(returnType.getOpcode(ILOAD), returnVar);
                }
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            Label handlerLabel = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handlerLabel, "java/lang/Throwable");
            mv.visitLabel(tryEnd);

            mv.visitLabel(handlerLabel);
            exceptionVar = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exceptionVar);
            generateExitCall(true);
            mv.visitVarInsn(ALOAD, exceptionVar);
            mv.visitInsn(ATHROW);

            super.visitMaxs(maxStack + 10, maxLocals + 6);
        }

        private void generateExitCall(boolean isException) {
            generateExitCall(isException, null, -1);
        }

        private void generateExitCall(boolean isException, Type returnType, int returnVar) {
            mv.visitLdcInsn(ttId);

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
            mv.visitVarInsn(ALOAD, paramsVar);

            if (isException) {
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
            } else {
                // returnObject (boxed)
                if (returnType == null) {
                    returnType = Type.getReturnType(methodDesc);
                }
                if (returnType.getSort() == Type.VOID || returnVar < 0) {
                    mv.visitInsn(ACONST_NULL);
                } else {
                    mv.visitVarInsn(returnType.getOpcode(ILOAD), returnVar);
                    if (returnType.getSort() != Type.OBJECT && returnType.getSort() != Type.ARRAY) {
                        box(returnType);
                    }
                }

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
        }
    }
}
