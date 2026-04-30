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
    public void missingStringOptionValueStillFails() {
        assertCode("E_ARGS_MISSING", "watch", "A", "m", "--condition");
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
    public void duplicateHiddenOptionAliasOrNameRejectedByBuilder() {
        try {
            CommandSpec.builder("dup")
                .option(OptionSpec.flag("visible").alias("--x").build())
                .hiddenOption(OptionSpec.string("hidden").alias("--x").build())
                .build();
            Assert.fail("Expected duplicate hidden option token to fail");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Duplicate option alias: --x"));
        }
    }

    @Test
    public void parserAcceptsHiddenOptionsWithoutPublicExposure() {
        CommandSpec spec = CommandSpec.builder("compat")
            .option(OptionSpec.flag("visible").alias("--visible").build())
            .hiddenOption(OptionSpec.string("removed").alias("--removed").build())
            .build();

        ParsedCommand parsed = CommandSpecParser.parse(spec, new String[] {"compat", "--removed", "old"});

        Assert.assertEquals("old", parsed.stringOption("removed"));
        Assert.assertEquals(1, spec.getOptions().size());
        Assert.assertEquals("visible", spec.getOptions().get(0).getName());
    }

    @Test
    public void parserAcceptsOptionalMissingStringOptionValues() {
        CommandSpec spec = CommandSpec.builder("compat")
            .hiddenOption(OptionSpec.string("removed").alias("--removed").missingValueAsEmptyString().build())
            .build();

        ParsedCommand bare = CommandSpecParser.parse(spec, new String[] {"compat", "--removed"});
        ParsedCommand equalsValue = CommandSpecParser.parse(spec, new String[] {"compat", "--removed=old"});
        ParsedCommand separateValue = CommandSpecParser.parse(spec, new String[] {"compat", "--removed", "old"});

        Assert.assertEquals("", bare.stringOption("removed"));
        Assert.assertEquals("old", equalsValue.stringOption("removed"));
        Assert.assertEquals("old", separateValue.stringOption("removed"));
    }

    @Test
    public void duplicateOptionInstanceRejectedByBuilder() {
        OptionSpec count = OptionSpec.integer("count").alias("-n").alias("--count").build();

        try {
            CommandSpec.builder("dup")
                .option(count)
                .option(count)
                .build();
            Assert.fail("Expected duplicate option instance to fail");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Duplicate option name: count"));
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
    public void invalidDefaultTypeRejectedByBuilder() {
        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.integer("count").defaultValue("5").build();
            }
        }, "Default value for option count");

        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.integer("count").defaultValue(1.5d).build();
            }
        }, "Default value for option count");
    }

    @Test
    public void outOfRangeDefaultRejectedByBuilder() {
        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.integer("count").defaultValue(0).range(1, 10).build();
            }
        }, "Default value for option count must be between 1 and 10");
    }

    @Test
    public void invalidRangeOrderRejectedByBuilder() {
        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.longNumber("duration").range(10, 1).build();
            }
        }, "Range minimum must not exceed maximum");
    }

    @Test
    public void rangeOnNonNumericOptionRejectedByBuilder() {
        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.string("name").range(1, 10).build();
            }
        }, "Range is only supported for numeric options");

        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.flag("verbose").range(0, 1).build();
            }
        }, "Range is only supported for numeric options");
    }

    @Test
    public void missingValueAsEmptyStringRejectedForNonStringOptions() {
        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.flag("verbose").missingValueAsEmptyString().build();
            }
        }, "Missing value as empty string is only supported for string options");

        assertInvalidOptionSpec(new Runnable() {
            @Override
            public void run() {
                OptionSpec.integer("count").missingValueAsEmptyString().build();
            }
        }, "Missing value as empty string is only supported for string options");
    }

    @Test
    public void validDefaultsParseToTypedValues() {
        CommandSpec spec = CommandSpec.builder("defaults")
            .meta(CommandMeta.operator(false, true))
            .option(OptionSpec.flag("enabled").defaultValue(Boolean.FALSE).build())
            .option(OptionSpec.string("name").defaultValue("sleuth").build())
            .option(OptionSpec.integer("count").defaultValue(100L).range(1, 100000).build())
            .option(OptionSpec.longNumber("duration").defaultValue(5000).range(1L, 86400000L).build())
            .build();

        ParsedCommand parsed = CommandSpecParser.parse(spec, new String[] {"defaults"});

        Assert.assertEquals(Boolean.FALSE, parsed.booleanOption("enabled"));
        Assert.assertEquals("sleuth", parsed.stringOption("name"));
        Assert.assertEquals(Integer.valueOf(100), parsed.intOption("count"));
        Assert.assertEquals(Long.valueOf(5000L), parsed.longOption("duration"));
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

    private static void assertInvalidOptionSpec(Runnable action, String messagePart) {
        try {
            action.run();
            Assert.fail("Expected invalid option spec");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains(messagePart));
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
