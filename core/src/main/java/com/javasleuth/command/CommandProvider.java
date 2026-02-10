package com.javasleuth.command;

import com.javasleuth.security.CommandMeta;
import java.util.Collections;
import java.util.Map;

public interface CommandProvider {
    String getName();

    Map<String, Command> getCommands();

    default Map<String, CommandMeta> getCommandMeta() {
        return Collections.emptyMap();
    }
}
