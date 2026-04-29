package com.javasleuth.command.spec;

import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecParseException;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.foundation.security.CommandMeta;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CommandSpecParserTest {
    @Test
    public void parsesAliasesDefaultsRepeatableOptionsAndArguments() {
        ParsedCommand parsed = CommandSpecParser.parse(sampleSpec(), new String[] {
            "watch", "A", "m", "-n", "5", "--condition", "cost:gt:1", "--condition=thread:eq:main", "--bg"
        });

        Assert.assertEquals("A", parsed.argument("class-pattern"));
        Assert.assertEquals("m", parsed.argument("method-pattern"));
        Assert.assertEquals(Integer.valueOf(5), parsed.intOption("count"));
        Assert.assertEquals(Boolean.TRUE, parsed.booleanOption("bg"));
        Assert.assertEquals(Arrays.asList("cost:gt:1", "thread:eq:main"), parsed.optionValues("condition"));
    }

    @Test
    public void appliesDefaultsBeforeExplicitValues() {
        ParsedCommand parsed = CommandSpecParser.parse(sampleSpec(), new String[] {"watch", "A", "m"});

        Assert.assertEquals(Integer.valueOf(100), parsed.intOption("count"));
    }

    @Test
    public void helpTokensRequestHelp() {
        Assert.assertTrue(CommandSpecParser.parse(sampleSpec(), new String[] {"watch", "--help"}).isHelpRequested());
        Assert.assertTrue(CommandSpecParser.parse(sampleSpec(), new String[] {"watch", "-h"}).isHelpRequested());
        Assert.assertTrue(CommandSpecParser.parse(sampleSpec(), new String[] {"watch", "help"}).isHelpRequested());
    }

    @Test
    public void missingOptionValueFails() {
        assertCode("E_ARGS_MISSING", "watch", "A", "m", "-n");
    }

    @Test
    public void unknownOptionFails() {
        assertCode("E_ARGS_UNKNOWN", "watch", "A", "m", "--missing");
    }

    @Test
    public void invalidIntegerFails() {
        assertCode("E_ARGS_INVALID", "watch", "A", "m", "--count", "abc");
    }

    @Test
    public void outOfRangeIntegerFails() {
        assertCode("E_ARGS_RANGE", "watch", "A", "m", "--count", "0");
    }

    @Test
    public void parsesNegativeIntegerOptionValue() {
        ParsedCommand parsed = CommandSpecParser.parse(negativeNumberSpec(), new String[] {"adjust", "--count", "-1"});

        Assert.assertEquals(Integer.valueOf(-1), parsed.intOption("count"));
    }

    @Test
    public void parsesNegativeLongOptionValue() {
        ParsedCommand parsed = CommandSpecParser.parse(negativeNumberSpec(), new String[] {"adjust", "--duration", "-9223372036854775808"});

        Assert.assertEquals(Long.valueOf(Long.MIN_VALUE), parsed.longOption("duration"));
    }

    @Test
    public void overflowingNegativeIntegerValueFailsAsInvalid() {
        assertNegativeNumberCode("E_ARGS_INVALID", "adjust", "--count", "-2147483649");
    }

    @Test
    public void duplicateNonRepeatableOptionFails() {
        assertCode("E_ARGS_DUPLICATE", "watch", "A", "m", "--count", "1", "--count=2");
    }

    @Test
    public void duplicateArgumentNameRejectedByBuilder() {
        try {
            CommandSpec.builder("dup")
                .argument(ArgumentSpec.required("name"))
                .argument(ArgumentSpec.optional("name"))
                .build();
            Assert.fail("Expected duplicate argument name to fail");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Duplicate argument name: name"));
        }
    }

    @Test
    public void duplicateOptionAliasOrNameRejectedByBuilder() {
        try {
            CommandSpec.builder("dup")
                .option(OptionSpec.flag("first").alias("--x").build())
                .option(OptionSpec.integer("x").build())
                .build();
            Assert.fail("Expected duplicate option token to fail");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Duplicate option name: x"));
        }
    }

    @Test
    public void repeatableOptionPreservesInputOrder() {
        ParsedCommand parsed = CommandSpecParser.parse(sampleSpec(), new String[] {
            "watch", "A", "m", "--condition", "first", "--condition=second", "--condition", "third"
        });

        Assert.assertEquals(Arrays.asList("first", "second", "third"), parsed.optionValues("condition"));
    }

    @Test
    public void exposesStringOptionsWithTypedAccessors() {
        ParsedCommand parsed = CommandSpecParser.parse(sampleSpec(), new String[] {
            "watch", "A", "m", "--condition", "first", "--condition=second"
        });

        String genericCondition = parsed.option("condition");
        List<String> genericConditions = parsed.optionValues("condition");
        List<String> stringConditions = parsed.stringOptionValues("condition");

        Assert.assertEquals("second", genericCondition);
        Assert.assertEquals("second", parsed.stringOption("condition"));
        Assert.assertEquals(Arrays.asList("first", "second"), genericConditions);
        Assert.assertEquals(Arrays.asList("first", "second"), stringConditions);
        try {
            stringConditions.add("third");
            Assert.fail("Expected string option values to be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void buildsArgumentSpecsWithFluentApi() {
        ArgumentSpec required = ArgumentSpec.builder("class-pattern").required(true).build();
        ArgumentSpec optional = ArgumentSpec.builder("limit").required(false).build();

        Assert.assertEquals("class-pattern", required.getName());
        Assert.assertTrue(required.isRequired());
        Assert.assertEquals("limit", optional.getName());
        Assert.assertFalse(optional.isRequired());
    }

    private static void assertCode(String code, String... args) {
        try {
            CommandSpecParser.parse(sampleSpec(), args);
            Assert.fail("Expected " + code);
        } catch (CommandSpecParseException e) {
            Assert.assertEquals(code, e.getCode());
            Assert.assertTrue(e.getMessage().startsWith(code + ": "));
        }
    }

    private static void assertNegativeNumberCode(String code, String... args) {
        try {
            CommandSpecParser.parse(negativeNumberSpec(), args);
            Assert.fail("Expected " + code);
        } catch (CommandSpecParseException e) {
            Assert.assertEquals(code, e.getCode());
            Assert.assertNotEquals("E_ARGS_MISSING", e.getCode());
            Assert.assertTrue(e.getMessage().startsWith(code + ": "));
        }
    }

    private static CommandSpec sampleSpec() {
        return CommandSpec.builder("watch")
            .description("Watch method execution")
            .usage("watch <class-pattern> <method-pattern> [options]")
            .meta(CommandMeta.operator(false, true))
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method-pattern"))
            .option(OptionSpec.integer("count").alias("-n").alias("--count").defaultValue(100).range(1, 100000).build())
            .option(OptionSpec.string("condition").alias("--condition").repeatable(true).build())
            .option(OptionSpec.flag("bg").alias("--bg").build())
            .build();
    }

    private static CommandSpec negativeNumberSpec() {
        return CommandSpec.builder("adjust")
            .meta(CommandMeta.operator(false, true))
            .option(OptionSpec.integer("count").range(-10, 10).build())
            .option(OptionSpec.longNumber("duration").range(Long.MIN_VALUE, Long.MAX_VALUE).build())
            .build();
    }
}
