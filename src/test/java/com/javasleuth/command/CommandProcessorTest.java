package com.javasleuth.command;

import com.javasleuth.command.protocol.BinaryFrame;
import com.javasleuth.command.protocol.BinaryFrameCodec;
import com.javasleuth.command.protocol.Frame;
import com.javasleuth.command.protocol.FrameCodec;
import com.javasleuth.security.AuthenticationManager;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.RequestSecurityManager;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, true);
        FrameCodec.writeFrame(pw, Frame.data("hello"), 4096);
        FrameCodec.writeFrame(pw, Frame.end(), 4096);

        BufferedReader br = new BufferedReader(new StringReader(sw.toString()));
        Frame data = FrameCodec.readFrame(br);
        assertNotNull(data);
        assertEquals(Frame.Type.DATA, data.getType());
        assertEquals("hello", data.getPayload());

        Frame end = FrameCodec.readFrame(br);
        assertNotNull(end);
        assertEquals(Frame.Type.END, end.getType());
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
    public void testAuthorizationManagerDynamicPermissionRegistration() {
        AuthorizationManager authorizationManager = AuthorizationManager.getInstance();
        AuthenticationManager authenticationManager = AuthenticationManager.getInstance();

        AuthenticationManager.AuthenticationResult session =
            authenticationManager.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
        assertTrue(session.isSuccess());

        CommandMeta meta = CommandMeta.viewer(false, false);
        authorizationManager.registerOrUpdatePermission("plugin_cmd", meta);

        AuthorizationManager.AuthorizationResult result =
            authorizationManager.authorize(session.getSessionId(), "plugin_cmd", new String[]{"plugin_cmd"});
        assertTrue(result.isAllowed());
    }

    @Test
    public void testAuthorizationManagerDangerousCommandUpgradesToAdmin() {
        AuthorizationManager authorizationManager = AuthorizationManager.getInstance();
        AuthenticationManager authenticationManager = AuthenticationManager.getInstance();

        AuthenticationManager.AuthenticationResult session =
            authenticationManager.createSession(AuthenticationManager.UserRole.VIEWER, "test-client");
        assertTrue(session.isSuccess());

        CommandMeta meta = CommandMeta.viewer(false, false).withDangerous(true);
        authorizationManager.registerOrUpdatePermission("dangerous_plugin_cmd", meta);

        AuthorizationManager.AuthorizationResult result =
            authorizationManager.authorize(session.getSessionId(), "dangerous_plugin_cmd", new String[]{"dangerous_plugin_cmd"});
        assertFalse(result.isAllowed());
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

            RequestSecurityManager mgr = RequestSecurityManager.getInstance();
            String nonce = "nonce123";
            long ts = System.currentTimeMillis();
            String signed = mgr.signCommand("help", ts, nonce);
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
