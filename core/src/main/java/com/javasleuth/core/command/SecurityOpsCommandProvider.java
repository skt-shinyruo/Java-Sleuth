package com.javasleuth.core.command;

import com.javasleuth.core.command.impl.AuditCommand;
import com.javasleuth.core.command.impl.AuthCommand;
import com.javasleuth.core.command.impl.PermCommand;
import com.javasleuth.core.command.impl.SessionCommand;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class SecurityOpsCommandProvider {
    Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        List<CommandDescriptor> descriptors = new ArrayList<>();
        BuiltinCommandMetas.add(
            descriptors,
            "audit",
            new AuditCommand(context.requireAuditLogger()),
            CommandMeta.admin(false, false).withAudit(false)
        );
        BuiltinCommandMetas.add(
            descriptors,
            "session",
            new SessionCommand(context.requireAuthenticationManager()),
            CommandMeta.viewer(false, false)
        );
        BuiltinCommandMetas.add(descriptors, "perm", new PermCommand(), CommandMeta.viewer(true, false));
        BuiltinCommandMetas.add(
            descriptors,
            "auth",
            new AuthCommand(context.requireAuthenticationManager()),
            CommandMeta.viewer(false, false)
        );
        return descriptors;
    }
}
