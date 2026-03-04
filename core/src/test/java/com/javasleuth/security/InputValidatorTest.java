package com.javasleuth.foundation.security;

import com.javasleuth.foundation.config.ProductionConfig;
import org.junit.Test;

import static org.junit.Assert.*;

public class InputValidatorTest {

    @Test
    public void testRedefineArgumentPositionsAreValidatedCorrectly() {
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.input.validation", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (AuditLogger auditLogger = new AuditLogger(config)) {
                InputValidator validator = new InputValidator(config, auditLogger);
                InputValidator.ValidationResult ok = validator.validateCommand(
                    "s1",
                    "test-client",
                    "redefine",
                    new String[]{"redefine", "com.example.TestService", "TestService.class"}
                );

                assertTrue(ok.isValid());
            }
        } finally {
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    @Test
    public void testRedefineRejectsInvalidClassName() {
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.input.validation", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (AuditLogger auditLogger = new AuditLogger(config)) {
                InputValidator validator = new InputValidator(config, auditLogger);
                InputValidator.ValidationResult bad = validator.validateCommand(
                    "s1",
                    "test-client",
                    "redefine",
                    new String[]{"redefine", "com.example.Bad Class", "Bad.class"}
                );

                assertFalse(bad.isValid());
                assertNotNull(bad.getMessage());
            }
        } finally {
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    @Test
    public void testMcRequiresJavaSourceFile() {
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.input.validation", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (AuditLogger auditLogger = new AuditLogger(config)) {
                InputValidator validator = new InputValidator(config, auditLogger);

                InputValidator.ValidationResult ok = validator.validateCommand(
                    "s1",
                    "test-client",
                    "mc",
                    new String[]{"mc", "Test.java"}
                );
                assertTrue(ok.isValid());

                InputValidator.ValidationResult bad = validator.validateCommand(
                    "s1",
                    "test-client",
                    "mc",
                    new String[]{"mc", "Test.class"}
                );
                assertFalse(bad.isValid());
            }
        } finally {
            setOrClearProperty("sleuth.security.input.validation", oldValidation);
        }
    }

    @Test
    public void testHeapdumpRejectsSystemPaths() {
        String oldValidation = System.getProperty("sleuth.security.input.validation");
        try {
            System.setProperty("sleuth.security.input.validation", "true");

            ProductionConfig config = ProductionConfig.createDefault();
            try (AuditLogger auditLogger = new AuditLogger(config)) {
                InputValidator validator = new InputValidator(config, auditLogger);
                InputValidator.ValidationResult bad = validator.validateCommand(
                    "s1",
                    "test-client",
                    "heapdump",
                    new String[]{"heapdump", "/etc/passwd"}
                );

                assertFalse(bad.isValid());
            }
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
}
