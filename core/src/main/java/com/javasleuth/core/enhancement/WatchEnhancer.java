package com.javasleuth.core.enhancement;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.util.Set;
import java.util.HashSet;
import com.javasleuth.foundation.util.WildcardMatcher;

public class WatchEnhancer implements BootstrapDependentEnhancer {
    private final String targetClassName;
    private final String targetClassInternalName;
    private final String targetMethodName;
    private final String targetMethodDesc;
    private final boolean captureParameters;
    private final boolean captureReturn;
    private final boolean captureException;
    private final String watchId;

    public WatchEnhancer(String className, String methodName, String methodDesc,
                        boolean captureParams, boolean captureReturn, boolean captureException, String watchId) {
        this.targetClassName = className;
        this.targetClassInternalName = className == null ? null : className.replace('.', '/');
        this.targetMethodName = methodName;
        this.targetMethodDesc = methodDesc;
        this.captureParameters = captureParams;
        this.captureReturn = captureReturn;
        this.captureException = captureException;
        this.watchId = watchId;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
        return new WatchClassVisitor(delegate, className);
    }

    @Override
    public String getDescription() {
        return "Watch enhancer for " + targetClassName + "." + targetMethodName;
    }

    @Override
    public String requiredBootstrapClassName() {
        return "com.javasleuth.bootstrap.spy.SleuthSpyAPI";
    }

    private class WatchClassVisitor extends ClassVisitor {
        private final String className;

        public WatchClassVisitor(ClassVisitor delegate, String className) {
            super(Opcodes.ASM9, delegate);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                       String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (shouldEnhanceMethod(name, descriptor)) {
                return new WatchMethodVisitor(mv, access, name, descriptor);
            }

            return mv;
        }

        private boolean shouldEnhanceMethod(String methodName, String methodDesc) {
            boolean nameMatches = WildcardMatcher.matches(methodName, targetMethodName);
            if (!nameMatches) {
                return false;
            }
            if (targetMethodDesc == null || "*".equals(targetMethodDesc)) {
                return true;
            }
            return targetMethodDesc.equals(methodDesc);
        }
    }

    private class WatchMethodVisitor extends AdviceAdapter {
        private final String methodName;
        private final String methodDesc;
        private int startTimeVar;
        private int exceptionVar;
        private int returnVar;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();

        public WatchMethodVisitor(MethodVisitor mv, int access, String methodName, String methodDesc) {
            super(Opcodes.ASM9, mv, access, methodName, methodDesc);
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        protected void onMethodEnter() {
            // Store start time
            startTimeVar = newLocal(Type.LONG_TYPE);
            // 先初始化本地变量，避免异常处理器看到未初始化的 top 类型导致 VerifyError
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, startTimeVar);

            mv.visitLabel(tryStart);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeVar);

            // Always emit METHOD_ENTRY; captureParameters only controls whether we collect parameter values.
            boolean parametersCaptured = captureParameters || Type.getArgumentTypes(methodDesc).length == 0;
            generateMethodEntryCall(parametersCaptured);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                // Always emit METHOD_EXIT for normal returns; captureReturn only controls whether we collect return value.
                Type returnType = Type.getReturnType(methodDesc);
                boolean returnCaptured = captureReturn || returnType.getSort() == Type.VOID;

                returnVar = -1;
                if (returnType.getSort() != Type.VOID) {
                    returnVar = newLocal(returnType);
                    mv.visitVarInsn(returnType.getOpcode(ISTORE), returnVar);
                }

                generateMethodExitCall(false, returnType, returnVar, returnCaptured);

                if (returnType.getSort() != Type.VOID) {
                    mv.visitVarInsn(returnType.getOpcode(ILOAD), returnVar);
                }
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (captureException) {
                // Add try-catch block for exception capture
                Label handlerLabel = new Label();

                mv.visitTryCatchBlock(tryStart, tryEnd, handlerLabel, "java/lang/Throwable");
                mv.visitLabel(tryEnd);
                mv.visitLabel(handlerLabel);
                exceptionVar = newLocal(Type.getType(Throwable.class));
                mv.visitVarInsn(ASTORE, exceptionVar);
                generateMethodExitCall(true);
                mv.visitVarInsn(ALOAD, exceptionVar);
                mv.visitInsn(ATHROW);
                super.visitMaxs(maxStack + 10, maxLocals + 5);
            } else {
                super.visitMaxs(maxStack + 10, maxLocals + 5);
            }
        }

        private void generateMethodEntryCall(boolean parametersCaptured) {
            // listenerId (watchId)
            mv.visitLdcInsn(watchId);

            // clazz
            if (targetClassInternalName != null) {
                mv.visitLdcInsn(Type.getObjectType(targetClassInternalName));
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // methodInfo: methodName|methodDesc
            mv.visitLdcInsn(methodName + "|" + methodDesc);

            // target (this or null)
            if ((methodAccess & ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // Push parameters array (or null if not captured)
            if (parametersCaptured) {
                generateParametersArray();
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // Push start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // SleuthSpyAPI.atEnter
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atEnter", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;J)V", false);
        }

        private void generateMethodExitCall(boolean isException) {
            generateMethodExitCall(isException, null, -1, true);
        }

        private void generateMethodExitCall(boolean isException, Type returnType, int returnVar, boolean returnCaptured) {
            // listenerId (watchId)
            mv.visitLdcInsn(watchId);

            // clazz
            if (targetClassInternalName != null) {
                mv.visitLdcInsn(Type.getObjectType(targetClassInternalName));
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // methodInfo: methodName|methodDesc
            mv.visitLdcInsn(methodName + "|" + methodDesc);

            // target (this or null)
            if ((methodAccess & ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // args (watch exit/exception does not include args; entry carries args when enabled)
            mv.visitInsn(ACONST_NULL);

            if (isException) {
                // throwable
                mv.visitVarInsn(ALOAD, exceptionVar);
            } else {
                // returnObject (boxed) or null if return capture is disabled.
                if (!returnCaptured) {
                    mv.visitInsn(ACONST_NULL);
                } else {
                    if (returnType == null) {
                        returnType = Type.getReturnType(methodDesc);
                    }
                    if (returnType.getSort() == Type.VOID || returnVar < 0) {
                        mv.visitInsn(ACONST_NULL);
                    } else {
                        mv.visitVarInsn(returnType.getOpcode(ILOAD), returnVar);
                        generateReturnValueBoxing(returnType);
                    }
                }
            }

            if (!isException) {
                // returnCaptured must be pushed before start/duration (method signature contract).
                push(returnCaptured);
            }

            // Push start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // Calculate duration
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);

            if (isException) {
                // SleuthSpyAPI.atExceptionExit
                mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                    "atExceptionExit", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Throwable;JJ)V", false);
            } else {
                // SleuthSpyAPI.atExit
                mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                    "atExit", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;ZJJ)V", false);
            }
        }

        private void generateParametersArray() {
            Type[] argumentTypes = Type.getArgumentTypes(methodDesc);

            // Create array
            push(argumentTypes.length);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

            // Fill array with parameters
            int paramIndex = (methodAccess & ACC_STATIC) == 0 ? 1 : 0; // Skip 'this' if not static

            for (int i = 0; i < argumentTypes.length; i++) {
                mv.visitInsn(DUP);
                push(i);

                Type argType = argumentTypes[i];
                mv.visitVarInsn(argType.getOpcode(ILOAD), paramIndex);

                // Box primitive types
                if (argType.getSort() != Type.OBJECT && argType.getSort() != Type.ARRAY) {
                    box(argType);
                }

                mv.visitInsn(AASTORE);
                paramIndex += argType.getSize();
            }
        }

        private void generateReturnValueBoxing(Type returnType) {
            if (returnType.getSort() != Type.OBJECT && returnType.getSort() != Type.ARRAY) {
                box(returnType);
            }
        }
    }
}
