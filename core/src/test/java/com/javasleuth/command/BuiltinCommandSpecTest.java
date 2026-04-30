package com.javasleuth.core.command;

import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
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

import java.util.ArrayList;
import java.util.List;
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

    private interface PipelineConsumer {
        void accept(CommandPipeline pipeline);
    }

    private interface ThrowingPipelineConsumer {
        void accept(CommandPipeline pipeline) throws Exception;
    }

    private static final class CapturingSink implements StreamSink {
        private final List<String> sent = new ArrayList<>();
        private int closeCount;
        private String lastCloseSummary;

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
