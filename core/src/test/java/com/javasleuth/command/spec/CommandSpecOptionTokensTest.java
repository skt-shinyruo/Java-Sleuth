package com.javasleuth.core.command.spec;

import com.javasleuth.foundation.security.CommandMeta;
import org.junit.Assert;
import org.junit.Test;

public class CommandSpecOptionTokensTest {
    @Test
    public void optionTokensIncludeCanonicalNameAndAliases() {
        CommandSpec spec = CommandSpec.builder("sample")
            .description("Sample command")
            .usage("sample [--bg]")
            .meta(CommandMeta.viewer(false, true))
            .option(OptionSpec.flag("bg").alias("--bg").alias("--background").build())
            .build();

        Assert.assertTrue(CommandSpecOptionTokens.hasOptionToken(
            new String[]{"sample", "--background"}, spec, "bg"));
        Assert.assertArrayEquals(
            new String[]{"sample", "target"},
            CommandSpecOptionTokens.removeOptionTokens(new String[]{"sample", "--background", "target", "--bg"}, spec, "bg")
        );
        Assert.assertFalse(CommandSpecOptionTokens.hasOptionToken(
            new String[]{"sample", "--background=true"}, spec, "bg"));
    }
}
