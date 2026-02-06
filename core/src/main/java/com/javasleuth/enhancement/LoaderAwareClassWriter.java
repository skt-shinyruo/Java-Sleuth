package com.javasleuth.enhancement;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * 基于目标 ClassLoader 的 ASM ClassWriter。
 *
 * <p>默认的 {@link ClassWriter#getCommonSuperClass(String, String)} 可能使用不正确的 ClassLoader，
 * 在复杂依赖或自定义 ClassLoader 场景下更容易触发 {@code COMPUTE_FRAMES} 失败。</p>
 */
public final class LoaderAwareClassWriter extends ClassWriter {
    private final ClassLoader loader;

    public LoaderAwareClassWriter(ClassReader classReader, int flags, ClassLoader loader) {
        super(classReader, flags);
        this.loader = loader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            Class<?> c = classForName(type1, loader);
            Class<?> d = classForName(type2, loader);

            if (c.isAssignableFrom(d)) {
                return type1;
            }
            if (d.isAssignableFrom(c)) {
                return type2;
            }
            if (c.isInterface() || d.isInterface()) {
                return "java/lang/Object";
            }

            Class<?> sc = c;
            while (sc != null && !sc.isAssignableFrom(d)) {
                sc = sc.getSuperclass();
            }
            if (sc == null) {
                return "java/lang/Object";
            }
            return sc.getName().replace('.', '/');
        } catch (Throwable t) {
            // 保守策略：无法解析依赖时不做“错误的 frames”，交由上层 transform 失败逻辑处理（冷却可重试）。
            throw new RuntimeException("Failed to resolve common super class: " + type1 + " vs " + type2, t);
        }
    }

    private static Class<?> classForName(String internalName, ClassLoader loader) throws ClassNotFoundException {
        if (internalName == null || internalName.isEmpty()) {
            return Object.class;
        }
        // 防御性处理：数组/描述符场景直接回退到 Object
        if (internalName.charAt(0) == '[') {
            return Object.class;
        }
        String name = internalName.replace('/', '.');
        return Class.forName(name, false, loader);
    }
}

