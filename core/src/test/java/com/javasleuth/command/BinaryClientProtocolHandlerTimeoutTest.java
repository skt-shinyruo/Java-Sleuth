package com.javasleuth.command;

import com.javasleuth.core.command.server.protocol.BinaryClientProtocolHandler;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class BinaryClientProtocolHandlerTimeoutTest {

    @Test
    public void handlerShouldIgnoreSocketTimeoutExceptionAndContinue() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        BinaryClientProtocolHandler handler = new BinaryClientProtocolHandler(running, null, null);

        InputStream timeoutThenEof = new InputStream() {
            private boolean first = true;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (first) {
                    first = false;
                    throw new SocketTimeoutException("simulated read timeout");
                }
                return -1; // EOF
            }

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                if (n <= 0) {
                    return -1;
                }
                return one[0] & 0xFF;
            }
        };

        DataInputStream in = new DataInputStream(timeoutThenEof);
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream());

        handler.handle(
            in,
            out,
            4096,
            "c1",
            "i1",
            "s1",
            "conn1",
            null,
            null,
            null
        );
    }
}

