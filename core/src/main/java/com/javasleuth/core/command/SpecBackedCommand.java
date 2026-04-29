package com.javasleuth.core.command;

import com.javasleuth.core.command.spec.CommandSpec;

public interface SpecBackedCommand {
    CommandSpec getSpec();
}
