package com.javasleuth.core.command;

import com.javasleuth.foundation.command.protocol.BinaryFrame;
import com.javasleuth.foundation.command.protocol.BinaryFrameCodec;
import com.javasleuth.foundation.command.protocol.Frame;
import com.javasleuth.foundation.command.protocol.FrameCodec;
import com.javasleuth.foundation.command.protocol.Utf8LineCodec;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.security.AuditLogger;
import com.javasleuth.foundation.security.AuthenticationManager;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import com.javasleuth.foundation.security.RequestSecurityManager;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.Assert.*;

public class CommandProcessorTest {

    @Test
    public void testCommandParserQuotes() {
        String line = "watch com.example.Service \"do Work\" -n 5";
        String[] parts = CommandParser.parse(line);

        assertEquals(5, parts.length);
        assertEquals("watch", parts[0]);
        assertEquals("com.example.Service", parts[1]);
        assertEquals("do Work", parts[2]);
        assertEquals("-n", parts[3]);
        assertEquals("5", parts[4]);
    }

        @Test
    public void testFrameCodecRoundTrip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, Frame.data("hello\nworld\n中文"), 10);
        FrameCodec.writeFrame(baos, Frame.end(), 10);

        BufferedReader br = new BufferedReader(
            new java.io.InputStreamReader(new ByteArrayInputStream(baos.toByteArray()), java.nio.charset.StandardCharsets.UTF_8)
        );

        StringBuilder sb = new StringBuilder();
        boolean firstLine = true;
        while (true) {
            Frame frame = FrameCodec.readFrame(br);
            assertNotNull(frame);
            if (frame.getType() == Frame.Type.DATA) {
                if (!firstLine) {
                    sb.append("\n");
                }
                sb.append(frame.getPayload());
                firstLine = false;
                continue;
            }
            if (frame.getType() == Frame.Type.END) {
                break;
            }
            fail("Unexpected frame type: " + frame.getType());
        }
        assertEquals("hello\nworld\n中文", sb.toString());
    }

    @Test
    public void testFrameCodecRoundTripStream() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FrameCodec.writeFrame(baos, Frame.data("hello"), 4096);
        FrameCodec.writeFrame(baos, Frame.end(), 4096);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        Frame data = FrameCodec.readFrame(bais, 8192);
        assertNotNull(data);
        assertEquals(Frame.Type.DATA, data.getType());
        assertEquals("hello", data.getPayload());

        Frame end = FrameCodec.readFrame(bais, 8192);
        assertNotNull(end);
        assertEquals(Frame.Type.END, end.getType());
    }

    @Test
    public void testUtf8LineCodecMaxBytes() throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean firstLine = true;
        for (int i = 0; i < 50; i++) {
            sb.append("a");
        }
        byte[] bytes = (sb.toString() + "\n").getBytes("UTF-8");

        try {
            Utf8LineCodec.readLine(new ByteArrayInputStream(bytes), 10);
            fail("Expected IOException for line too long");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void testBinaryFrameCodecRoundTrip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        String payload = "hello\nworld\n中文";
        BinaryFrameCodec.writeFrame(dos, BinaryFrame.data(payload), 10);
        BinaryFrameCodec.writeFrame(dos, BinaryFrame.end(), 10);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        StringBuilder out = new StringBuilder();
        while (true) {
            BinaryFrame frame = BinaryFrameCodec.readFrame(dis, 10);
            assertNotNull(frame);
            if (frame.getType() == BinaryFrame.Type.DATA) {
                out.append(frame.getPayloadUtf8());
            } else if (frame.getType() == BinaryFrame.Type.END) {
                break;
            } else {
                fail("Unexpected frame type: " + frame.getType());
            }
        }

        assertEquals(payload, out.toString());
    }

    @Test
    public void testBinaryFrameCodecRejectsOversizeRequestFrame() throws Exception {
        int max = 16;
        byte[] payload = new byte[max + 1];
        BinaryFrame request = BinaryFrame.of(BinaryFrame.Type.CMD, 0, payload);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        BinaryFrameCodec.writeFrame(dos, request, max);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        try {
            BinaryFrameCodec.readFrame(dis, max);
            fail("Expected IOException for oversize frame payload");
        } catch (IOException expected) {
            assertTrue(String.valueOf(expected.getMessage()).toLowerCase().contains("payload too large"));
        }
    }

    @Test
    public void testCommandTimeoutInPipeline() {
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        String oldTimeout = System.getProperty("sleuth.performance.command.timeout");

        try {
            System.setProperty("sleuth.security.authorization.enabled", "false");
            System.setProperty("sleuth.security.input.validation", "false");
            System.setProperty("sleuth.performance.command.timeout", "50");

            ProductionConfig config = ProductionConfig.getInstance();
            AuditLogger auditLogger = AuditLogger.getInstance();
            AuthenticationManager authn = AuthenticationManager.getInstance();
            AuthorizationManager authz = new AuthorizationManager(config, auditLogger, authn);
            DangerousCommandConfirmationManager dangerousConfirm = DangerousCommandConfirmationManager.getInstance();
            InputValidator validator = new InputValidator(config, auditLogger);
            CommandPipeline pipeline = new CommandPipeline(validator, authz, dangerousConfirm, config);

            Command slow = new Command() {
                @Override
                public String execute(String[] args) throws Exception {
                    Thread.sleep(200);
                    return "ok";
                }

                @Override
                public String getDescription() {
                    return "slow";
                }
            };

            CommandRegistry.Entry entry = new CommandRegistry.Entry(slow, CommandMeta.viewer(false, false), "test");
            CommandContext context = new CommandContext("c1", "test", null, false);
            CommandPipeline.Result result = pipeline.execute(entry, new String[]{"slow"}, context);
            assertFalse(result.isSuccess());
            assertNotNull(result.getError());
            assertTrue(result.getError().toLowerCase().contains("timed out"));
        } finally {
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
            setOrClearProperty("sleuth.performance.command.timeout", oldTimeout);
        }
    }

    @Test
    public void testAuthorizationManagerDynamicPermissionRegistration() {
        String oldAnon = System.getProperty("sleuth.security.anonymous.viewer");
        AuthorizationManager authorizationManager = AuthorizationManager.createDefault();
        AuthenticationManager authenticationManager = AuthenticationManager.getInstance();

        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");

            AuthenticationManager.AuthenticationResult session =
                authenticationManager.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
            assertTrue(session.isSuccess());

            CommandMeta meta = CommandMeta.viewer(false, false);
            AuthorizationManager.AuthorizationResult result =
                authorizationManager.authorize(session.getSessionId(), "plugin_cmd", new String[]{"plugin_cmd"}, meta);
            assertTrue(result.isAllowed());
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnon);
        }
    }

    @Test
    public void testAuthorizationManagerDangerousCommandUpgradesToAdmin() {
        String oldAnon = System.getProperty("sleuth.security.anonymous.viewer");
        String oldAuthz = System.getProperty("sleuth.security.authorization.enabled");
        AuthorizationManager authorizationManager = AuthorizationManager.createDefault();
        AuthenticationManager authenticationManager = AuthenticationManager.getInstance();

        try {
            System.setProperty("sleuth.security.anonymous.viewer", "true");
            System.setProperty("sleuth.security.authorization.enabled", "true");

            AuthenticationManager.AuthenticationResult session =
                authenticationManager.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
            assertTrue(session.isSuccess());

            CommandMeta meta = CommandMeta.viewer(false, false).withDangerous(true);
            AuthorizationManager.AuthorizationResult result =
                authorizationManager.authorize(session.getSessionId(), "dangerous_plugin_cmd", new String[]{"dangerous_plugin_cmd"}, meta);
            assertFalse(result.isAllowed());
        } finally {
            setOrClearProperty("sleuth.security.anonymous.viewer", oldAnon);
            setOrClearProperty("sleuth.security.authorization.enabled", oldAuthz);
        }
    }

    @Test
    public void testHmacSignAndVerifyAndReplayProtection() {
        String oldMode = System.getProperty("sleuth.security.mode");
        String oldSecret = System.getProperty("sleuth.security.hmac.secret");
        String oldWindow = System.getProperty("sleuth.security.hmac.timestamp.window.ms");

        try {
            System.setProperty("sleuth.security.mode", "hmac");
            System.setProperty("sleuth.security.hmac.secret", "test-secret");
            System.setProperty("sleuth.security.hmac.timestamp.window.ms", "60000");

            RequestSecurityManager mgr = RequestSecurityManager.createDefault();
            String nonce = "nonce123";
            long ts = System.currentTimeMillis();
            String signed = mgr.signCommand("help", ts, nonce, "test-sid");
            assertNotNull(signed);
            assertTrue(signed.startsWith("SIG "));

            RequestSecurityManager.VerificationResult ok = mgr.verifyAndExtract("s1", signed);
            assertTrue(ok.isOk());
            assertEquals("help", ok.getCommand());

            RequestSecurityManager.VerificationResult replay = mgr.verifyAndExtract("s1", signed);
            assertFalse(replay.isOk());
        } finally {
            setOrClearProperty("sleuth.security.mode", oldMode);
            setOrClearProperty("sleuth.security.hmac.secret", oldSecret);
            setOrClearProperty("sleuth.security.hmac.timestamp.window.ms", oldWindow);
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
