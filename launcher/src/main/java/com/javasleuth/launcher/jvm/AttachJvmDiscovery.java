package com.javasleuth.launcher.jvm;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Attach API 的 JVM 发现实现。
 *
 * <p>默认过滤掉 Java-Sleuth 自身进程，避免误选/自我 attach。</p>
 */
public final class AttachJvmDiscovery implements JvmDiscovery {

    @Override
    public List<VirtualMachineDescriptor> listAttachableCandidates() {
        List<VirtualMachineDescriptor> vmList = VirtualMachine.list();
        if (vmList == null || vmList.isEmpty()) {
            return new ArrayList<>();
        }

        List<VirtualMachineDescriptor> candidates = new ArrayList<>();
        for (VirtualMachineDescriptor vm : vmList) {
            if (vm == null) {
                continue;
            }
            if (isSleuthProcess(vm)) {
                continue;
            }
            candidates.add(vm);
        }
        return candidates;
    }

    @Override
    public VirtualMachineDescriptor findByPid(String pid) {
        if (pid == null || pid.trim().isEmpty()) {
            return null;
        }
        String wanted = pid.trim();
        List<VirtualMachineDescriptor> vmList = VirtualMachine.list();
        if (vmList == null || vmList.isEmpty()) {
            return null;
        }
        for (VirtualMachineDescriptor vm : vmList) {
            if (vm == null) {
                continue;
            }
            if (!wanted.equals(vm.id())) {
                continue;
            }
            if (isSleuthProcess(vm)) {
                return null;
            }
            return vm;
        }
        return null;
    }

    private static boolean isSleuthProcess(VirtualMachineDescriptor vm) {
        String displayName = vm.displayName();
        if (displayName == null) {
            displayName = "";
        }
        return displayName.contains("SleuthLauncher") || displayName.contains("java-sleuth");
    }
}

