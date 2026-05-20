package com.javasleuth.core.agent.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Assert;
import org.junit.Test;

public class BootstrapBridgeContractStatusTest {

    @Test
    public void relaxedModeReportsClasspathVisibleContractVersion() {
        Assert.assertFalse(BootstrapBridge.isStrictMode());

        String status = BootstrapBridge.describeContractStatus();

        Assert.assertTrue(status, status.startsWith("OK"));
        Assert.assertTrue(status, status.contains("contract=1"));
        Assert.assertTrue(status, status.contains("classpath-visible"));
    }

    @Test
    public void relaxedModeReadsBootstrapContractVersionFromClasspath() {
        Assert.assertFalse(BootstrapBridge.isStrictMode());

        Assert.assertEquals(1, BootstrapBridge.bootstrapContractVersion());
    }

    @Test
    public void publicBridgeContractDoesNotExposeLegacyInterceptorsAsRuntimeDependencies() throws Exception {
        for (Field field : BootstrapBridge.class.getFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String value = (String) field.get(null);
            Assert.assertFalse(
                field.getName() + " should not expose compatibility-only bootstrap interceptors",
                value.startsWith("com.javasleuth.bootstrap.monitor.") && value.endsWith("Interceptor")
            );
        }
    }
}
