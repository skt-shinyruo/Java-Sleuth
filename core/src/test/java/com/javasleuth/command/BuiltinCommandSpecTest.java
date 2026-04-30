package com.javasleuth.core.command;

import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecParseException;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.impl.TraceCommand;
import com.javasleuth.core.command.impl.VmToolCommand;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.monitoring.MetricsCollector;
import com.javasleuth.core.spy.SleuthSpyDispatcher;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import org.junit.Assert;
import org.junit.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class BuiltinCommandSpecTest {
    @Test
    public void descriptorCanCarrySpecMeta() {
        CommandSpec spec = CommandSpec.builder("sample")
            .description("Sample command")
            .usage("sample [--flag]")
            .meta(CommandMeta.viewer(false, false))
            .option(OptionSpec.flag("flag").alias("--flag").build())
            .build();
        Command command = new Command() {
            @Override
            public String execute(String[] args) {
                return "ok";
            }

            @Override
            public String getDescription() {
                return "Sample command";
            }
        };

        CommandDescriptor descriptor = CommandDescriptor.ofSpec(spec, command);
        Assert.assertEquals("sample", descriptor.getName());
        Assert.assertSame(spec, descriptor.getSpec());
        Assert.assertSame(spec.getMeta(), descriptor.getMeta());
    }

    @Test
    public void instrumentationCommandsExposeSpecs() {
        withProviderContext(context -> {
            BuiltinCommandProvider provider = new BuiltinCommandProvider();
            Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(context);
            assertHasSpec(descriptors, "watch");
            assertHasSpec(descriptors, "trace");
            assertHasSpec(descriptors, "monitor");
            assertHasSpec(descriptors, "tt");
            assertHasSpec(descriptors, "stack");
            assertHasSpec(descriptors, "vmtool");
        });
    }

    @Test
    public void operationalBatchCommandsExposeSpecs() {
        withProviderContext(context -> {
            BuiltinCommandProvider provider = new BuiltinCommandProvider();
            Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(context);
            assertSpecBackedDescriptor(descriptors, "jobs");
            assertSpecBackedDescriptor(descriptors, "config");
            assertSpecBackedDescriptor(descriptors, "audit");
            assertSpecBackedDescriptor(descriptors, "logger");
            assertSpecBackedDescriptor(descriptors, "vmoption");
        });
    }

    @Test
    public void operationalBatchSpecsCoverSubcommandsAndValidatedOptions() {
        withProviderContext(context -> {
            BuiltinCommandProvider provider = new BuiltinCommandProvider();
            Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(context);

            CommandSpec jobs = requiredSpec(descriptors, "jobs");
            Assert.assertNotNull(jobs.subcommand("list"));
            Assert.assertNotNull(jobs.subcommand("tail"));
            Assert.assertNotNull(jobs.subcommand("stop"));
            expectParseFailure(jobs, new String[]{"jobs", "tail", "job-1", "--lines", "NaN"}, "E_ARGS_INVALID");

            CommandSpec config = requiredSpec(descriptors, "config");
            Assert.assertNotNull(config.subcommand("get"));
            Assert.assertNotNull(config.subcommand("set"));
            Assert.assertNotNull(config.subcommand("save"));
            ParsedCommand save = CommandSpecParser.parse(config, new String[]{"config", "save", "--include-overrides"});
            Assert.assertEquals("save", save.subcommandName());
            Assert.assertTrue(save.booleanOption("include-runtime"));

            CommandSpec audit = requiredSpec(descriptors, "audit");
            Assert.assertNotNull(audit.subcommand("tail"));
            Assert.assertNotNull(audit.subcommand("security"));
            Assert.assertNotNull(audit.subcommand("search"));
            expectParseFailure(audit, new String[]{"audit", "search", "COMMAND", "--lines", "bad"}, "E_ARGS_INVALID");

            CommandSpec logger = requiredSpec(descriptors, "logger");
            Assert.assertNotNull(logger.subcommand("list"));
            Assert.assertNotNull(logger.subcommand("set"));
            expectParseFailure(logger, new String[]{"logger", "list", "--limit", "bad"}, "E_ARGS_INVALID");

            CommandSpec vmoption = requiredSpec(descriptors, "vmoption");
            Assert.assertNotNull(vmoption.subcommand("list"));
            Assert.assertNotNull(vmoption.subcommand("get"));
            Assert.assertNotNull(vmoption.subcommand("set"));
            expectParseFailure(vmoption, new String[]{"vmoption", "list", "--limit", "bad"}, "E_ARGS_INVALID");
            ParsedCommand patternFallback = CommandSpecParser.parse(vmoption, new String[]{"vmoption", "*GC*"});
            Assert.assertNull(patternFallback.subcommandName());
            Assert.assertEquals("*GC*", patternFallback.argument("pattern"));
        });
    }

    @Test
    public void diagnosticBatchCommandsExposeSpecs() {
        withProviderContext(context -> {
            BuiltinCommandProvider provider = new BuiltinCommandProvider();
            Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(context);
            assertSpecBackedDescriptor(descriptors, "sc");
            assertSpecBackedDescriptor(descriptors, "sm");
            assertSpecBackedDescriptor(descriptors, "classloader");
            assertSpecBackedDescriptor(descriptors, "mbean");
            assertSpecBackedDescriptor(descriptors, "heapdump");
            assertSpecBackedDescriptor(descriptors, "dump");
            assertSpecBackedDescriptor(descriptors, "jad");
        });
    }

    @Test
    public void diagnosticBatchSpecsCoverRepresentativeSyntax() {
        withProviderContext(context -> {
            BuiltinCommandProvider provider = new BuiltinCommandProvider();
            Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(context);

            CommandSpec sc = requiredSpec(descriptors, "sc");
            Assert.assertEquals("com.example.*", CommandSpecParser.parse(sc, new String[]{"sc", "com.example.*", "-d", "-f"})
                .argument("class-pattern"));
            expectParseFailure(sc, new String[]{"sc", "com.example.*", "--unknown"}, "E_ARGS_UNKNOWN");

            CommandSpec sm = requiredSpec(descriptors, "sm");
            ParsedCommand smParsed = CommandSpecParser.parse(sm, new String[]{"sm", "com.example.*", "do*", "-E", "-d"});
            Assert.assertEquals("do*", smParsed.argument("method-pattern"));
            Assert.assertTrue(smParsed.booleanOption("regex"));

            CommandSpec classloader = requiredSpec(descriptors, "classloader");
            Assert.assertNotNull(classloader.subcommand("classes"));
            Assert.assertNotNull(classloader.subcommand("find"));
            ParsedCommand classloaderShortcut = CommandSpecParser.parse(classloader, new String[]{"classloader", "java.lang.*"});
            Assert.assertNull(classloaderShortcut.subcommandName());
            Assert.assertEquals("java.lang.*", classloaderShortcut.argument("class-pattern"));

            CommandSpec mbean = requiredSpec(descriptors, "mbean");
            Assert.assertNotNull(mbean.subcommand("list"));
            Assert.assertNotNull(mbean.subcommand("invoke"));
            ParsedCommand mbeanShortcut = CommandSpecParser.parse(mbean, new String[]{"mbean", "java.lang:*"});
            Assert.assertNull(mbeanShortcut.subcommandName());
            Assert.assertEquals("java.lang:*", mbeanShortcut.argument("pattern"));
            ParsedCommand mbeanSet = CommandSpecParser.parse(
                mbean,
                new String[]{"mbean", "set", "java.lang:type=Threading", "ThreadContentionMonitoringEnabled", "-1"}
            );
            Assert.assertEquals("set", mbeanSet.subcommandName());
            Assert.assertEquals("-1", mbeanSet.argument("value"));

            CommandSpec heapdump = requiredSpec(descriptors, "heapdump");
            ParsedCommand heapdumpParsed = CommandSpecParser.parse(heapdump, new String[]{"heapdump", "--all", "--file=/tmp/app.hprof"});
            Assert.assertTrue(heapdumpParsed.booleanOption("all"));
            Assert.assertEquals("/tmp/app.hprof", heapdumpParsed.stringOption("file"));

            CommandSpec dump = requiredSpec(descriptors, "dump");
            Assert.assertEquals("./out", CommandSpecParser.parse(dump, new String[]{"dump", "com.example.*", "--output", "./out"})
                .stringOption("output"));
            expectParseFailure(dump, new String[]{"dump", "com.example.*", "--limit", "NaN"}, "E_ARGS_INVALID");

            CommandSpec jad = requiredSpec(descriptors, "jad");
            ParsedCommand jadParsed = CommandSpecParser.parse(jad, new String[]{"jad", "com.example.App", "--method=run", "--lines"});
            Assert.assertEquals("run", jadParsed.stringOption("method"));
            Assert.assertTrue(jadParsed.booleanOption("lines"));
            expectParseFailure(jad, new String[]{"jad", "com.example.App", "--max-lines", "bad"}, "E_ARGS_INVALID");
        });
    }

    @Test
    public void remainingInstrumentationSpecsCoverRepresentativeSyntax() {
        withProviderContext(context -> {
            BuiltinCommandProvider provider = new BuiltinCommandProvider();
            Collection<CommandDescriptor> descriptors = provider.getCommandDescriptors(context);

            CommandSpec stack = requiredSpec(descriptors, "stack");
            ParsedCommand stackParsed = CommandSpecParser.parse(
                stack,
                new String[]{"stack", "com.example.*", "doWork", "-n", "5", "-t", "30", "--depth", "25", "--bg"}
            );
            Assert.assertEquals("com.example.*", stackParsed.argument("class-pattern"));
            Assert.assertEquals(Integer.valueOf(5), stackParsed.intOption("count"));
            Assert.assertEquals(Long.valueOf(30L), stackParsed.longOption("timeout"));
            Assert.assertEquals(Integer.valueOf(25), stackParsed.intOption("depth"));
            Assert.assertTrue(stackParsed.booleanOption("bg"));
            expectParseFailure(stack, new String[]{"stack", "com.example.*", "doWork", "--depth", "0"}, "E_ARGS_RANGE");

            CommandSpec tt = requiredSpec(descriptors, "tt");
            ParsedCommand ttDefault = CommandSpecParser.parse(
                tt,
                new String[]{"tt", "com.example.*", "doWork", "-n", "3", "--bg"}
            );
            Assert.assertNull(ttDefault.subcommandName());
            Assert.assertEquals("com.example.*", ttDefault.argument("class-pattern"));
            Assert.assertEquals(Integer.valueOf(3), ttDefault.intOption("count"));
            Assert.assertTrue(ttDefault.booleanOption("bg"));

            ParsedCommand ttRecord = CommandSpecParser.parse(
                tt,
                new String[]{"tt", "record", "com.example.*", "doWork", "--timeout", "10"}
            );
            Assert.assertEquals("record", ttRecord.subcommandName());
            Assert.assertEquals(Long.valueOf(10L), ttRecord.longOption("timeout"));
            Assert.assertEquals("doWork", ttRecord.argument("method-pattern"));

            Assert.assertEquals("20", CommandSpecParser.parse(tt, new String[]{"tt", "list", "20"}).argument("limit"));
            Assert.assertEquals("42", CommandSpecParser.parse(tt, new String[]{"tt", "detail", "42"}).argument("record-id"));
            Assert.assertEquals("42", CommandSpecParser.parse(tt, new String[]{"tt", "replay", "42"}).argument("record-id"));
            Assert.assertEquals("tt-1", CommandSpecParser.parse(tt, new String[]{"tt", "stop", "tt-1"}).argument("tt-id"));
            Assert.assertEquals("clear", CommandSpecParser.parse(tt, new String[]{"tt", "clear"}).subcommandName());
            expectParseFailure(tt, new String[]{"tt", "record", "com.example.*", "doWork", "-n", "bad"}, "E_ARGS_INVALID");
        });
    }

    @Test
    public void traceSpecDoesNotExposeRemovedSampleOptions() {
        Set<String> publicOptionNames = new HashSet<>();
        for (OptionSpec option : TraceCommand.spec().getOptions()) {
            publicOptionNames.add(option.getName());
        }

        Assert.assertFalse(publicOptionNames.contains("sample"));
        Assert.assertFalse(publicOptionNames.contains("sample-rate"));
    }

    @Test
    public void traceHelpDoesNotExposeRemovedSampleOptions() {
        String help = CommandHelpRenderer.render(TraceCommand.spec());

        Assert.assertFalse(help.contains("--sample"));
        Assert.assertFalse(help.contains("--sample-rate"));
    }

    @Test
    public void vmtoolExposesSubcommandSpec() {
        CommandSpec spec = VmToolCommand.spec();
        Assert.assertNotNull(spec.subcommand("track"));
        Assert.assertNotNull(spec.subcommand("invoke"));
        Assert.assertNotNull(spec.subcommand("invoke-static"));
        Assert.assertNotNull(spec.subcommand("invokestatic"));
        Assert.assertEquals(CommandMeta.ImpactLevel.MEDIUM, spec.getMeta().getImpactLevel());
    }

    @Test
    public void syncPipelineExposesParsedSpecOptionsInContext() {
        withPipeline(pipeline -> {
            CommandSpec spec = sampleSpec();
            Command command = new Command() {
                @Override
                public String execute(String[] args) {
                    Boolean flag = CommandContextHolder.get().getParsedCommand().booleanOption("flag");
                    return "flag=" + flag;
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);

            CommandPipeline.Result result = pipeline.executePrechecked(
                entry,
                new String[]{"sample", "--flag"},
                new CommandContext("c", "i", "s", false)
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertEquals("flag=true", result.getOutput());
        });
    }

    @Test
    public void syncPipelineReturnsSpecHelpWithoutExecutingCommand() {
        withPipeline(pipeline -> {
            CommandSpec spec = sampleSpec();
            AtomicBoolean executed = new AtomicBoolean(false);
            Command command = new Command() {
                @Override
                public String execute(String[] args) {
                    executed.set(true);
                    return "executed";
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);

            CommandPipeline.Result result = pipeline.executePrechecked(
                entry,
                new String[]{"sample", "--help"},
                new CommandContext("c", "i", "s", false)
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertEquals(CommandHelpRenderer.render(spec), result.getOutput());
            Assert.assertFalse(executed.get());
        });
    }

    @Test
    public void syncPipelineReturnsSpecParseErrorsWithoutCorrelationId() {
        withPipeline(pipeline -> {
            CommandSpec spec = sampleSpec();
            AtomicBoolean executed = new AtomicBoolean(false);
            Command command = new Command() {
                @Override
                public String execute(String[] args) {
                    executed.set(true);
                    return "executed";
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);

            CommandPipeline.Result result = pipeline.executePrechecked(
                entry,
                new String[]{"sample", "--flag=true"},
                new CommandContext("c", "i", "s", false)
            );

            Assert.assertFalse(result.isSuccess());
            Assert.assertNull(result.getOutput());
            Assert.assertNotNull(result.getError());
            Assert.assertTrue(result.getError().contains("E_ARGS_INVALID"));
            Assert.assertFalse(result.getError().contains("errorId="));
            Assert.assertFalse(executed.get());
        });
    }

    @Test
    public void syncPipelineSanitizesSpecHelpOutput() {
        withValidationEnabled(() -> withPipeline(pipeline -> {
            CommandSpec spec = CommandSpec.builder("sample")
                .description("Sample\u0000 command")
                .usage("sample [--flag]")
                .meta(CommandMeta.viewer(false, true))
                .option(OptionSpec.flag("flag").alias("--flag").build())
                .build();
            Command command = new Command() {
                @Override
                public String execute(String[] args) {
                    return "executed";
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);

            CommandPipeline.Result result = pipeline.executePrechecked(
                entry,
                new String[]{"sample", "--help"},
                new CommandContext("c", "i", "s", false)
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertEquals(CommandHelpRenderer.render(spec).replace("\u0000", ""), result.getOutput());
            Assert.assertFalse(result.getOutput().contains("\u0000"));
        }));
    }

    @Test
    public void streamPipelineReturnsSpecHelpWithoutExecutingCommand() throws Exception {
        withPipelineThrows(pipeline -> {
            CommandSpec spec = sampleSpec();
            AtomicBoolean executed = new AtomicBoolean(false);
            StreamCommand command = new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) {
                    executed.set(true);
                    sink.send("executed");
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);
            CapturingSink sink = new CapturingSink();

            CommandPipeline.StreamResult result = pipeline.executeStreamPrechecked(
                entry,
                new String[]{"sample", "--help"},
                new CommandContext("c", "i", "s", true),
                sink
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertEquals(1, sink.sent.size());
            Assert.assertEquals(CommandHelpRenderer.render(spec), sink.sent.get(0));
            Assert.assertEquals(1, sink.closeCount);
            Assert.assertNull(sink.lastCloseSummary);
            Assert.assertFalse(executed.get());
        });
    }

    @Test
    public void streamPipelineReturnsSpecParseErrorsWithoutCorrelationId() throws Exception {
        withPipelineThrows(pipeline -> {
            CommandSpec spec = sampleSpec();
            AtomicBoolean executed = new AtomicBoolean(false);
            StreamCommand command = new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) {
                    executed.set(true);
                    sink.send("executed");
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);
            CapturingSink sink = new CapturingSink();

            CommandPipeline.StreamResult result = pipeline.executeStreamPrechecked(
                entry,
                new String[]{"sample", "--flag=true"},
                new CommandContext("c", "i", "s", true),
                sink
            );

            Assert.assertFalse(result.isSuccess());
            Assert.assertNotNull(result.getError());
            Assert.assertTrue(result.getError().contains("E_ARGS_INVALID"));
            Assert.assertFalse(result.getError().contains("errorId="));
            Assert.assertEquals(result.getError(), sink.lastError);
            Assert.assertFalse(executed.get());
        });
    }

    @Test
    public void streamPipelineSpecHelpDoesNotEmitCloseSummaryAsDataFrame() throws Exception {
        withPipelineThrows(pipeline -> {
            CommandSpec spec = sampleSpec();
            StreamCommand command = new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) {
                    sink.send("executed");
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);
            ProtocolLikeSink sink = new ProtocolLikeSink();

            CommandPipeline.StreamResult result = pipeline.executeStreamPrechecked(
                entry,
                new String[]{"sample", "--help"},
                new CommandContext("c", "i", "s", true),
                sink
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertEquals(1, sink.sent.size());
            Assert.assertEquals(CommandHelpRenderer.render(spec), sink.sent.get(0));
            Assert.assertEquals(1, sink.closeCount);
        });
    }

    @Test
    public void streamPipelineSanitizesSpecHelpOutputWithoutCloseSummaryPayload() throws Exception {
        withValidationEnabledThrows(() -> withPipelineThrows(pipeline -> {
            CommandSpec spec = CommandSpec.builder("sample")
                .description("Sample\u0000 command")
                .usage("sample [--flag]")
                .meta(CommandMeta.viewer(false, true))
                .option(OptionSpec.flag("flag").alias("--flag").build())
                .build();
            StreamCommand command = new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) {
                    sink.send("executed");
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);
            ProtocolLikeSink sink = new ProtocolLikeSink();

            CommandPipeline.StreamResult result = pipeline.executeStreamPrechecked(
                entry,
                new String[]{"sample", "--help"},
                new CommandContext("c", "i", "s", true),
                sink
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertEquals(1, sink.sent.size());
            Assert.assertEquals(CommandHelpRenderer.render(spec).replace("\u0000", ""), sink.sent.get(0));
            Assert.assertFalse(sink.sent.get(0).contains("\u0000"));
            Assert.assertEquals(1, sink.closeCount);
        }));
    }

    @Test
    public void syncPipelineSpecCommandSessionMutationPropagatesToOriginalContext() {
        withPipeline(pipeline -> {
            CommandSpec spec = sampleSpec();
            Command command = new Command() {
                @Override
                public String execute(String[] args) {
                    CommandContextHolder.get().setSessionId("new-session");
                    return "ok";
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);
            CommandContext context = new CommandContext("c", "i", "s", false);

            CommandPipeline.Result result = pipeline.executePrechecked(
                entry,
                new String[]{"sample", "--flag"},
                context
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertEquals("new-session", context.getSessionId());
        });
    }

    @Test
    public void contextCopiesShareSessionMutationsAndPreserveParsedCommand() {
        CommandSpec spec = sampleSpec();
        CommandContext context = new CommandContext("c", "i", "s", false);
        CommandContext parsed = context.withParsedCommand(com.javasleuth.core.command.spec.CommandSpecParser.parse(
            spec,
            new String[]{"sample", "--flag"}
        ));
        CancellationToken token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void throwIfCancelled() {
            }
        };
        CommandContext withToken = parsed.withCancellationToken(token);

        withToken.setSessionId("new-session");

        Assert.assertEquals("new-session", context.getSessionId());
        Assert.assertSame(parsed.getParsedCommand(), withToken.getParsedCommand());
        Assert.assertTrue(withToken.getParsedCommand().booleanOption("flag"));
        Assert.assertSame(token, withToken.getCancellationToken());
    }

    @Test
    public void streamPipelinePreservesParsedCommandWhenCancellationTokenIsAdded() throws Exception {
        withPipelineThrows(pipeline -> {
            CommandSpec spec = sampleSpec();
            StreamCommand command = new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) {
                    Boolean flag = CommandContextHolder.get().getParsedCommand().booleanOption("flag");
                    boolean hasToken = CommandContextHolder.get().getCancellationToken() != CancellationToken.NONE;
                    sink.send("flag=" + flag + ",token=" + hasToken);
                }

                @Override
                public String getDescription() {
                    return "Sample command";
                }
            };
            CommandRegistry.Entry entry = new CommandRegistry.Entry(command, spec.getMeta(), "test", null, null, spec);
            CapturingSink sink = new CapturingSink();

            CommandPipeline.StreamResult result = pipeline.executeStreamPrechecked(
                entry,
                new String[]{"sample", "--flag"},
                new CommandContext("c", "i", "s", true),
                sink
            );

            Assert.assertTrue(result.isSuccess());
            Assert.assertNotNull(result.getHandle());
            Assert.assertTrue(result.getHandle().awaitCompletion().isSuccess());
            Assert.assertEquals(1, sink.sent.size());
            Assert.assertEquals("flag=true,token=true", sink.sent.get(0));
        });
    }

    private static CommandSpec sampleSpec() {
        return CommandSpec.builder("sample")
            .description("Sample command")
            .usage("sample [--flag]")
            .meta(CommandMeta.viewer(false, true))
            .option(OptionSpec.flag("flag").alias("--flag").build())
            .build();
    }

    private static void assertHasSpec(Collection<CommandDescriptor> descriptors, String commandName) {
        for (CommandDescriptor descriptor : descriptors) {
            if (commandName.equals(descriptor.getName())) {
                Assert.assertNotNull(commandName + " should expose a command spec", descriptor.getSpec());
                return;
            }
        }
        Assert.fail("Missing built-in command descriptor for " + commandName);
    }

    private static CommandSpec requiredSpec(Collection<CommandDescriptor> descriptors, String commandName) {
        for (CommandDescriptor descriptor : descriptors) {
            if (commandName.equals(descriptor.getName())) {
                Assert.assertNotNull(commandName + " should expose a command spec", descriptor.getSpec());
                return descriptor.getSpec();
            }
        }
        Assert.fail("Missing built-in command descriptor for " + commandName);
        return null;
    }

    private static void assertSpecBackedDescriptor(Collection<CommandDescriptor> descriptors, String commandName) {
        for (CommandDescriptor descriptor : descriptors) {
            if (commandName.equals(descriptor.getName())) {
                Assert.assertNotNull(commandName + " should expose a command spec", descriptor.getSpec());
                Assert.assertTrue(commandName + " command should implement SpecBackedCommand",
                    descriptor.getCommand() instanceof SpecBackedCommand);
                Assert.assertSame(descriptor.getSpec(), ((SpecBackedCommand) descriptor.getCommand()).getSpec());
                Assert.assertSame(descriptor.getSpec().getMeta(), descriptor.getMeta());
                return;
            }
        }
        Assert.fail("Missing built-in command descriptor for " + commandName);
    }

    private static void expectParseFailure(CommandSpec spec, String[] args, String code) {
        try {
            CommandSpecParser.parse(spec, args);
            Assert.fail("Expected parse failure " + code);
        } catch (CommandSpecParseException expected) {
            Assert.assertEquals(code, expected.getCode());
        }
    }

    private static void withProviderContext(ProviderContextConsumer consumer) {
        ProductionConfig config = ProductionConfig.createDefault();
        Instrumentation instrumentation = fakeInstrumentation();
        SleuthClassFileTransformer transformer = new SleuthClassFileTransformer(config);
        MetricsCollector metricsCollector = new MetricsCollector(config);
        JobManager jobManager = new JobManager();
        SleuthSpyDispatcher spyDispatcher = new SleuthSpyDispatcher();
        VmToolSessionRegistry vmToolSessionRegistry = new VmToolSessionRegistry(spyDispatcher);
        PerformanceOptimizer performanceOptimizer = new PerformanceOptimizer(config);
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager authenticationManager = new AuthenticationManager(config, auditLogger);
            DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger)
        ) {
            consumer.accept(new CommandProviderContext(
                instrumentation,
                transformer,
                metricsCollector,
                config,
                auditLogger,
                null,
                authenticationManager,
                dangerousConfirm,
                jobManager,
                vmToolSessionRegistry,
                performanceOptimizer,
                spyDispatcher,
                new EnhancementSessionRegistry()
            ));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            vmToolSessionRegistry.shutdown(instrumentation, transformer, "test cleanup");
            jobManager.shutdown("test cleanup");
            metricsCollector.shutdown();
            performanceOptimizer.close();
        }
    }

    private static Instrumentation fakeInstrumentation() {
        return (Instrumentation) Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[0];
                }
                if ("isModifiableClass".equals(name)) {
                    return true;
                }
                if ("removeTransformer".equals(name)) {
                    return true;
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == Void.TYPE) {
                    return null;
                }
                if (returnType == Boolean.TYPE) {
                    return false;
                }
                if (returnType == Integer.TYPE) {
                    return 0;
                }
                if (returnType == Long.TYPE) {
                    return 0L;
                }
                if (returnType.isArray()) {
                    return java.lang.reflect.Array.newInstance(returnType.getComponentType(), 0);
                }
                return null;
            }
        );
    }

    private static void withPipeline(PipelineConsumer consumer) {
        try {
            withPipelineThrows(pipeline -> consumer.accept(pipeline));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void withPipelineThrows(ThrowingPipelineConsumer consumer) throws Exception {
        ProductionConfig config = ProductionConfig.createDefault();
        try (
            AuditLogger auditLogger = new AuditLogger(config);
            AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
            DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger);
            PerformanceOptimizer optimizer = new PerformanceOptimizer(config)
        ) {
            AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
            InputValidator validator = new InputValidator(config, auditLogger);
            CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config, optimizer);
            try {
                consumer.accept(pipeline);
            } finally {
                pipeline.shutdown();
            }
        }
    }

    private static void withValidationEnabled(Runnable runnable) {
        try {
            withValidationEnabledThrows(() -> runnable.run());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void withValidationEnabledThrows(ThrowingRunnable runnable) throws Exception {
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.input.validation", "true");
            runnable.run();
        } finally {
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private interface PipelineConsumer {
        void accept(CommandPipeline pipeline);
    }

    private interface ProviderContextConsumer {
        void accept(CommandProviderContext context) throws Exception;
    }

    private interface ThrowingPipelineConsumer {
        void accept(CommandPipeline pipeline) throws Exception;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class CapturingSink implements StreamSink {
        private final List<String> sent = new ArrayList<>();
        private int closeCount;
        private String lastCloseSummary;
        private String lastError;

        @Override
        public void send(String data) {
            sent.add(data);
        }

        @Override
        public void close(String summary) {
            closeCount++;
            lastCloseSummary = summary;
        }

        @Override
        public void error(String message) {
            lastError = message;
        }
    }

    private static final class ProtocolLikeSink implements StreamSink {
        private final List<String> sent = new ArrayList<>();
        private int closeCount;

        @Override
        public void send(String data) {
            sent.add(data);
        }

        @Override
        public void close(String summary) {
            if (summary != null && !summary.isEmpty()) {
                sent.add(summary);
            }
            closeCount++;
        }

        @Override
        public void error(String message) {
        }
    }
}
