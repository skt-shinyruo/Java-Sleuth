package com.javasleuth.core.enhancement;

import com.javasleuth.foundation.util.WildcardMatcher;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * StackEnhancer 用于在目标方法入口处采集调用栈（简化版）。
 *
 * <p>仅插入 onMethodEnter，避免对返回值/异常路径产生额外影响。</p>
 */
public final class StackEnhancer implements BootstrapDependentEnhancer {
    private final String targetClassName;
    private final String targetClassInternalName;
    private final String targetMethodPattern;
    private final String targetMethodDesc;
    private final String stackId;

    public StackEnhancer(String className, String methodPattern, String methodDesc, String stackId) {
        this.targetClassName = className;
        this.targetClassInternalName = className == null ? null : className.replace('.', '/');
        this.targetMethodPattern = methodPattern;
        this.targetMethodDesc = methodDesc;
        this.stackId = stackId;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String className) {
        return new StackClassVisitor(delegate);
    }

    @Override
    public String getDescription() {
        return "Stack enhancer for " + targetClassName + "." + targetMethodPattern;
    }

    @Override
    public String requiredBootstrapClassName() {
        return "com.javasleuth.bootstrap.spy.SleuthSpyAPI";
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
            // listenerId (stackId)
            mv.visitLdcInsn(stackId);

            // clazz
            if (targetClassInternalName != null) {
                mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(targetClassInternalName));
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

            // args
            mv.visitInsn(ACONST_NULL);

            // startNanos (not used by stack)
            mv.visitInsn(LCONST_0);

            mv.visitMethodInsn(INVOKESTATIC,
                "com/javasleuth/bootstrap/spy/SleuthSpyAPI",
                "atEnter",
                "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;J)V",
                false
            );
        }
    }
}
