package com.javasleuth.core.command;

import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CommandPipelineStreamExecutionTest {

    private static final class CapturingSink implements StreamSink {
        private final List<String> sent = new ArrayList<>();
        private int closeCount = 0;
        private int errorCount = 0;
        private String lastError;

        @Override
        public void send(String data) {
            sent.add(data);
        }

        @Override
        public void close(String summary) {
            closeCount++;
        }

        @Override
        public void error(String message) {
            errorCount++;
            lastError = message;
        }
    }

    @Test
    public void streamOutput_isSanitizedAndSinkIsClosed() throws Exception {
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.input.validation", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                InputValidator validator = new InputValidator(config, auditLogger);
                CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config);
                try {

            StreamCommand cmd = new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) {
                    sink.send("a\u0000b");
                }

                @Override
                public String getDescription() {
                    return "test";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(cmd, CommandMeta.viewer(false, true), "test");
            CapturingSink sink = new CapturingSink();
            CommandPipeline.StreamResult r = pipeline.executeStreamPrechecked(entry, new String[]{"watch"}, new CommandContext("c", "i", "s", true), sink);

            assertNotNull(r);
            assertTrue(r.isSuccess());
            assertEquals(1, sink.sent.size());
            assertEquals("ab", sink.sent.get(0));
            assertEquals(1, sink.closeCount);
            assertEquals(0, sink.errorCount);
                } finally {
                    pipeline.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    @Test
    public void streamTimeout_sendsErrorAndDoesNotClose() throws Exception {
        String oldTimeout = System.getProperty("sleuth.performance.command.timeout");
        String oldTimeoutMax = System.getProperty("sleuth.performance.command.timeout.max");
        try {
            System.setProperty("sleuth.performance.command.timeout", "50");
            System.setProperty("sleuth.performance.command.timeout.max", "50");

            ProductionConfig config = ProductionConfig.createDefault();
            try (
                AuditLogger auditLogger = new AuditLogger(config);
                AuthenticationManager authn = new AuthenticationManager(config, auditLogger);
                DangerousCommandConfirmationManager dangerousConfirm = new DangerousCommandConfirmationManager(config, auditLogger)
            ) {
                AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
                InputValidator validator = new InputValidator(config, auditLogger);
                CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config);
                try {

            StreamCommand cmd = new StreamCommand() {
                @Override
                public String execute(String[] args) {
                    return "";
                }

                @Override
                public void executeStream(String[] args, StreamSink sink) throws Exception {
                    Thread.sleep(200);
                }

                @Override
                public String getDescription() {
                    return "sleep";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(cmd, CommandMeta.viewer(false, true), "test");
            CapturingSink sink = new CapturingSink();
            CommandPipeline.StreamResult r = pipeline.executeStreamPrechecked(entry, new String[]{"watch"}, new CommandContext("c", "i", "s", true), sink);

            assertNotNull(r);
            assertFalse(r.isSuccess());
            assertEquals(0, sink.closeCount);
            assertEquals(1, sink.errorCount);
            assertNotNull(sink.lastError);
            assertTrue(sink.lastError.toLowerCase().contains("timed out"));
                } finally {
                    pipeline.shutdown();
                }
            }
        } finally {
            setOrClearProperty("sleuth.performance.command.timeout", oldTimeout);
            setOrClearProperty("sleuth.performance.command.timeout.max", oldTimeoutMax);
        }
    }

    private static void setOrClearProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
