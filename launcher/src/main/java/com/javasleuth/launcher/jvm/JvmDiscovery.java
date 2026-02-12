package com.javasleuth.launcher.jvm;

import com.sun.tools.attach.VirtualMachineDescriptor;
import java.util.List;

/**
 * JVM 发现与过滤。
 *
 * <p>将进程枚举与过滤策略从 SleuthLauncher 中拆出，便于测试与演进。</p>
 */
public interface JvmDiscovery {
    List<VirtualMachineDescriptor> listAttachableCandidates();

    VirtualMachineDescriptor findByPid(String pid);
}

