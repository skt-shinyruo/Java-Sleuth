package com.javasleuth.core.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class BuiltinCommandProvider implements CommandProvider {
    @Override
    public String getName() {
        return "builtin";
    }

    @Override
    public CommandProviderInfo getInfo() {
        return CommandProviderInfo.builtin(
            "builtin",
            Collections.singletonList("core-diagnostics")
        );
    }

    @Override
    public Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        List<CommandDescriptor> descriptors = new ArrayList<>();
        descriptors.addAll(new JvmDiagnosticsCommandProvider().getCommandDescriptors(context));
        descriptors.addAll(new EnhancementCommandProvider().getCommandDescriptors(context));
        descriptors.addAll(new RuntimeMutationCommandProvider().getCommandDescriptors(context));
        descriptors.addAll(new SecurityOpsCommandProvider().getCommandDescriptors(context));
        descriptors.addAll(new OperationsCommandProvider().getCommandDescriptors(context));
        return descriptors;
    }
}
