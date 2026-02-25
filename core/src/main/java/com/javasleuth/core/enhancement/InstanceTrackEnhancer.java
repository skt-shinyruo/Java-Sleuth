package com.javasleuth.core.enhancement;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 在构造器返回时上报实例（vmtool track）。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>只在“真正调用 super(...) 的构造器”返回时上报，避免 this(...) 构造链重复上报。</li>
 *   <li>上报逻辑必须 best-effort，不影响业务构造器语义。</li>
 * </ul>
 */
public class InstanceTrackEnhancer implements BootstrapDependentEnhancer {
    private final String trackId;
    private final String className;

    public InstanceTrackEnhancer(String trackId, String className) {
        this.trackId = trackId;
        this.className = className;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassVisitor delegate, String ignored) {
        return new TrackClassVisitor(delegate);
    }

    @Override
    public String getDescription() {
        return "VmTool instance tracker for " + className;
    }

    @Override
    public String requiredBootstrapClassName() {
        return "com.javasleuth.bootstrap.monitor.VmToolInterceptor";
    }

    private class TrackClassVisitor extends ClassVisitor {
        private String internalName;

        TrackClassVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.internalName = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("<init>".equals(name)) {
                return new CtorVisitor(mv, access, name, descriptor, internalName);
            }
            return mv;
        }
    }

    private class CtorVisitor extends AdviceAdapter {
        private final String ownerInternalName;
        private String firstInitOwner;

        protected CtorVisitor(MethodVisitor mv, int access, String name, String desc, String ownerInternalName) {
            super(Opcodes.ASM9, mv, access, name, desc);
            this.ownerInternalName = ownerInternalName;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (firstInitOwner == null && opcode == INVOKESPECIAL && "<init>".equals(name)) {
                firstInitOwner = owner;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != RETURN) {
                return;
            }

            // If this constructor delegates to another ctor in the same class via this(...),
            // the first invokespecial <init> owner will be the current class.
            if (firstInitOwner != null && firstInitOwner.equals(ownerInternalName)) {
                return;
            }

            // Call VmToolInterceptor.onConstructed(trackId, this)
            mv.visitLdcInsn(trackId);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                "com/javasleuth/bootstrap/monitor/VmToolInterceptor",
                "onConstructed",
                "(Ljava/lang/String;Ljava/lang/Object;)V",
                false);
        }
    }
}
