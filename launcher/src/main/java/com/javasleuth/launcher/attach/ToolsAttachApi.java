package com.javasleuth.launcher.attach;

import com.sun.tools.attach.VirtualMachine;

/**
 * 基于 com.sun.tools.attach.VirtualMachine 的默认实现。
 */
public final class ToolsAttachApi implements AttachApi {

    @Override
    public VirtualMachineHandle attach(String pid) throws Exception {
        final VirtualMachine vm = VirtualMachine.attach(pid);
        return new VirtualMachineHandle() {
            @Override
            public void loadAgent(String agentPath, String agentArgs) throws Exception {
                vm.loadAgent(agentPath, agentArgs);
            }

            @Override
            public void detach() throws Exception {
                vm.detach();
            }
        };
    }
}

