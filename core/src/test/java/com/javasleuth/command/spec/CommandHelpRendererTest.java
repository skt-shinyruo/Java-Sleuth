package com.javasleuth.command.spec;

import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.foundation.security.CommandMeta;
import org.junit.Assert;
import org.junit.Test;

public class CommandHelpRendererTest {
    @Test
    public void rendersUsageAliasesDefaultsRangeAndExamples() {
        CommandSpec spec = CommandSpec.builder("monitor")
            .description("Monitor method statistics")
            .usage("monitor <class-pattern> <method-pattern> [options]")
            .meta(CommandMeta.operator(false, true))
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method-pattern"))
            .option(OptionSpec.longNumber("interval").alias("-i").alias("--interval").defaultValue(5000L).range(1L, 86400000L).build())
            .example("monitor *Service* doWork -i 1000")
            .build();

        String help = CommandHelpRenderer.render(spec);

        Assert.assertTrue(help.contains("monitor"));
        Assert.assertTrue(help.contains("Monitor method statistics"));
        Assert.assertTrue(help.contains("Usage: monitor <class-pattern> <method-pattern> [options]"));
        Assert.assertTrue(help.contains("class-pattern"));
        Assert.assertTrue(help.contains("method-pattern"));
        Assert.assertTrue(help.contains("-i, --interval"));
        Assert.assertTrue(help.contains("default: 5000"));
        Assert.assertTrue(help.contains("range: 1..86400000"));
        Assert.assertTrue(help.contains("monitor *Service* doWork -i 1000"));
    }
}
