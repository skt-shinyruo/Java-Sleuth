package com.javasleuth.core.enhancement;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import com.javasleuth.foundation.util.WildcardMatcher;

public class TraceEnhancer implements BootstrapDependentEnhancer {
    private final String targetClassName;
    private final String targetClassInternalName;
    private final String targetMethodName;
    private final String targetMethodDesc;
    private final String traceId;

    public TraceEnhancer(String className, String methodName, String methodDesc, String traceId) {
        this.targetClassName = className;
        this.targetClassInternalName = className == null ? null : className.replace('.', '/');
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

    @Override
    public String requiredBootstrapClassName() {
        return "com.javasleuth.bootstrap.spy.SleuthSpyAPI";
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

    private class TraceMethodVisitor extends AdviceAdapter {
        private final String methodName;
        private final String methodDesc;
        private int startTimeVar;
        private int currentLine = -1;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();

        public TraceMethodVisitor(MethodVisitor mv, int access, String methodName, String methodDesc) {
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
            // Store start time
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LSTORE, startTimeVar);

            // Call trace interceptor for method entry
            generateTraceEntryCall();
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != ATHROW) {
                generateTraceExitCall();
            }
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            currentLine = line;
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == INVOKEVIRTUAL || opcode == INVOKESPECIAL || opcode == INVOKESTATIC || opcode == INVOKEINTERFACE) {
                if (shouldTraceInvoke(owner, name, descriptor)) {
                    generateInvokeEventsAround(opcode, owner, name, descriptor, isInterface, currentLine);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Add try-catch for exception tracing
            Label handlerLabel = new Label();

            mv.visitTryCatchBlock(tryStart, tryEnd, handlerLabel, "java/lang/Throwable");
            mv.visitLabel(tryEnd);
            mv.visitLabel(handlerLabel);
            int exceptionVar = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exceptionVar);
            generateTraceExceptionExitCall(exceptionVar);
            mv.visitVarInsn(ALOAD, exceptionVar);
            mv.visitInsn(ATHROW);
            super.visitMaxs(maxStack + 10, maxLocals + 3);
        }

        private void generateTraceEntryCall() {
            // listenerId (traceId)
            mv.visitLdcInsn(traceId);

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

            // args (trace does not capture args for now)
            mv.visitInsn(ACONST_NULL);

            // Push start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // SleuthSpyAPI.atEnter
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atEnter", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;J)V", false);
        }

        private void generateTraceExitCall() {
            // listenerId (traceId)
            mv.visitLdcInsn(traceId);

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

            // args (trace does not capture args for now)
            mv.visitInsn(ACONST_NULL);

            // returnObject (trace ignores return value)
            mv.visitInsn(ACONST_NULL);

            // returnCaptured (true; return value is not used)
            push(true);

            // Push start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // Calculate duration
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);

            // SleuthSpyAPI.atExit
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atExit", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;ZJJ)V", false);
        }

        private void generateTraceExceptionExitCall(int exceptionVar) {
            // listenerId (traceId)
            mv.visitLdcInsn(traceId);

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

            // args (trace does not capture args for now)
            mv.visitInsn(ACONST_NULL);

            // throwable
            mv.visitVarInsn(ALOAD, exceptionVar);

            // start time
            mv.visitVarInsn(LLOAD, startTimeVar);

            // duration = nanoTime - startTimeVar
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(LLOAD, startTimeVar);
            mv.visitInsn(LSUB);

            // SleuthSpyAPI.atExceptionExit
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atExceptionExit", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Throwable;JJ)V", false);
        }

        private boolean shouldTraceInvoke(String owner, String name, String descriptor) {
            if (owner == null) {
                return false;
            }
            if (name == null) {
                return false;
            }
            if (owner.startsWith("java/") || owner.startsWith("javax/") ||
                owner.startsWith("sun/") || owner.startsWith("com/javasleuth/")) {
                return false;
            }

            // Constructor invokes have an uninitialized object on the stack. Injecting any method call before
            // invokespecial <init> may cause VerifyError. For safety, skip constructor invokes.
            if ("<init>".equals(name)) {
                return false;
            }

            // Avoid duplicate events when the callee method is also traced by this enhancer.
            if (targetClassInternalName != null && targetClassInternalName.equals(owner)) {
                boolean nameMatches = WildcardMatcher.matches(name, targetMethodName);
                if (nameMatches) {
                    if (targetMethodDesc == null || "*".equals(targetMethodDesc) || targetMethodDesc.equals(descriptor)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void generateInvokeEventsAround(
            int opcode,
            String owner,
            String name,
            String descriptor,
            boolean isInterface,
            int lineNumber
        ) {
            generateBeforeInvoke(owner, name, descriptor, lineNumber);

            Label invokeStart = new Label();
            Label invokeEnd = new Label();
            Label invokeHandler = new Label();
            Label afterHandler = new Label();

            mv.visitTryCatchBlock(invokeStart, invokeEnd, invokeHandler, "java/lang/Throwable");
            mv.visitLabel(invokeStart);
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            mv.visitLabel(invokeEnd);

            Type returnType = Type.getReturnType(descriptor);
            int returnVar = -1;
            if (returnType.getSort() != Type.VOID) {
                returnVar = newLocal(returnType);
                mv.visitVarInsn(returnType.getOpcode(ISTORE), returnVar);
            }

            // Skip the exception handler on the normal path.
            mv.visitJumpInsn(GOTO, afterHandler);

            // Exception handler for the invoke instruction only.
            mv.visitLabel(invokeHandler);
            int exceptionVar = newLocal(Type.getType(Throwable.class));
            mv.visitVarInsn(ASTORE, exceptionVar);
            generateInvokeException(owner, name, descriptor, lineNumber, exceptionVar);
            mv.visitVarInsn(ALOAD, exceptionVar);
            mv.visitInsn(ATHROW);

            // Normal completion: emit after-invoke and restore return value.
            mv.visitLabel(afterHandler);
            generateAfterInvoke(owner, name, descriptor, lineNumber);
            if (returnVar >= 0) {
                mv.visitVarInsn(returnType.getOpcode(ILOAD), returnVar);
            }
        }

        private void generateBeforeInvoke(String owner, String name, String descriptor, int lineNumber) {
            // listenerId (traceId)
            mv.visitLdcInsn(traceId);

            // clazz (enclosing class)
            if (targetClassInternalName != null) {
                mv.visitLdcInsn(Type.getObjectType(targetClassInternalName));
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // invokeInfo: owner|name|desc|line
            mv.visitLdcInsn(owner + "|" + name + "|" + descriptor + "|" + lineNumber);

            // target (this or null)
            if ((methodAccess & ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // whenNanos
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);

            // SleuthSpyAPI.atBeforeInvoke
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atBeforeInvoke", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;J)V", false);
        }

        private void generateAfterInvoke(String owner, String name, String descriptor, int lineNumber) {
            // listenerId (traceId)
            mv.visitLdcInsn(traceId);

            // clazz (enclosing class)
            if (targetClassInternalName != null) {
                mv.visitLdcInsn(Type.getObjectType(targetClassInternalName));
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // invokeInfo: owner|name|desc|line
            mv.visitLdcInsn(owner + "|" + name + "|" + descriptor + "|" + lineNumber);

            // target (this or null)
            if ((methodAccess & ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // whenNanos
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);

            // SleuthSpyAPI.atAfterInvoke
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atAfterInvoke", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;J)V", false);
        }

        private void generateInvokeException(String owner, String name, String descriptor, int lineNumber, int exceptionVar) {
            // listenerId (traceId)
            mv.visitLdcInsn(traceId);

            // clazz (enclosing class)
            if (targetClassInternalName != null) {
                mv.visitLdcInsn(Type.getObjectType(targetClassInternalName));
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // invokeInfo: owner|name|desc|line
            mv.visitLdcInsn(owner + "|" + name + "|" + descriptor + "|" + lineNumber);

            // target (this or null)
            if ((methodAccess & ACC_STATIC) == 0) {
                mv.visitVarInsn(ALOAD, 0);
            } else {
                mv.visitInsn(ACONST_NULL);
            }

            // throwable
            mv.visitVarInsn(ALOAD, exceptionVar);

            // whenNanos
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);

            // SleuthSpyAPI.atInvokeException
            mv.visitMethodInsn(INVOKESTATIC, "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atInvokeException", "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Throwable;J)V", false);
        }
    }
}
