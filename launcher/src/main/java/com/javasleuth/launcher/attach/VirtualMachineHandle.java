package com.javasleuth.launcher.attach;

/**
 * Attach API 的最小可替换抽象。
 *
 * <p>用于将 SleuthLauncher 的“业务编排”与具体的 com.sun.tools.attach.VirtualMachine 解耦，便于单测。</p>
 */
public interface VirtualMachineHandle {
    void loadAgent(String agentPath, String agentArgs) throws Exception;

    void detach() throws Exception;
}

