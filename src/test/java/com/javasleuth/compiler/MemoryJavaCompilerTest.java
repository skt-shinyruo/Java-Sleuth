package com.javasleuth.compiler;

import org.junit.Assume;
import org.junit.Test;

import javax.tools.ToolProvider;
import java.util.Map;

import static org.junit.Assert.*;

public class MemoryJavaCompilerTest {
    @Test
    public void compileCollectsClassBytes() throws Exception {
        Assume.assumeNotNull(ToolProvider.getSystemJavaCompiler());
        String src =
            "package com.example;\n" +
            "public class Hello {\n" +
            "  public int add(int a, int b) { return a + b; }\n" +
            "}\n";

        try (MemoryJavaCompiler compiler = new MemoryJavaCompiler()) {
            MemoryJavaCompiler.CompilationResult r = compiler.compile("com.example.Hello", src);
            if (!r.isSuccess()) {
                StringBuilder msg = new StringBuilder();
                msg.append("Compilation failed. Diagnostics:\n");
                for (Object d : r.getDiagnostics()) {
                    msg.append(String.valueOf(d)).append("\n");
                }
                fail(msg.toString());
            }
            Map<String, byte[]> classes = r.getCompiledClasses();
            assertTrue("compiled classes should contain com.example.Hello", classes.containsKey("com.example.Hello"));
            assertTrue(classes.get("com.example.Hello").length > 0);
        }
    }
}
