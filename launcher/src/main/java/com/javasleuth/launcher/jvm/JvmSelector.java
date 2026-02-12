package com.javasleuth.launcher.jvm;

import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;
import java.util.List;

/**
 * JVM 选择器（交互 UI 抽象）。
 *
 * <p>实现可以基于 JLine/控制台/GUI 等，不应混入协议或 Attach 逻辑。</p>
 */
public interface JvmSelector {
    VirtualMachineDescriptor select(List<VirtualMachineDescriptor> candidates) throws IOException;
}

