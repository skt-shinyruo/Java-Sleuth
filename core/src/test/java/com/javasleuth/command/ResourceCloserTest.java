package com.javasleuth.core.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ResourceCloserTest {

    @Test
    public void fromOwnedResources_closesOwnedResourcesInLifecycleOrder() throws Exception {
        List<String> closed = new ArrayList<>();
        ResourceOwnership ownership = new ResourceOwnership(true, true, true, true, true, true, true, true);

        AutoCloseable closer = ResourceCloser.fromOwnedResources(
            ownership,
            recording(closed, "audit"),
            recording(closed, "authn"),
            recording(closed, "dangerous"),
            recording(closed, "perf"),
            recording(closed, "vmtool"),
            recording(closed, "client"),
            recording(closed, "job"),
            recording(closed, "enhancement")
        );

        Assert.assertNotNull(closer);
        closer.close();

        Assert.assertEquals(
            Arrays.asList("enhancement", "job", "client", "vmtool", "perf", "dangerous", "authn", "audit"),
            closed
        );
    }

    @Test
    public void fromOwnedResources_skipsInjectedResources() throws Exception {
        List<String> closed = new ArrayList<>();
        ResourceOwnership ownership = new ResourceOwnership(false, false, false, false, false, false, true, true);

        AutoCloseable closer = ResourceCloser.fromOwnedResources(
            ownership,
            recording(closed, "audit"),
            recording(closed, "authn"),
            recording(closed, "dangerous"),
            recording(closed, "perf"),
            recording(closed, "vmtool"),
            recording(closed, "client"),
            recording(closed, "job"),
            recording(closed, "enhancement")
        );

        Assert.assertNotNull(closer);
        closer.close();

        Assert.assertEquals(Arrays.asList("enhancement", "job"), closed);
    }

    @Test
    public void fromOwnedResources_returnsNullWhenNothingOwned() {
        ResourceOwnership ownership = new ResourceOwnership(false, false, false, false, false, false, false, false);

        AutoCloseable closer = ResourceCloser.fromOwnedResources(
            ownership,
            recording(new ArrayList<String>(), "audit"),
            recording(new ArrayList<String>(), "authn"),
            recording(new ArrayList<String>(), "dangerous"),
            recording(new ArrayList<String>(), "perf"),
            recording(new ArrayList<String>(), "vmtool"),
            recording(new ArrayList<String>(), "client"),
            recording(new ArrayList<String>(), "job"),
            recording(new ArrayList<String>(), "enhancement")
        );

        Assert.assertNull(closer);
    }

    @Test
    public void fromOwnedResources_isIdempotent() throws Exception {
        List<String> closed = new ArrayList<>();
        ResourceOwnership ownership = new ResourceOwnership(false, false, false, false, false, false, false, true);

        AutoCloseable closer = ResourceCloser.fromOwnedResources(
            ownership,
            recording(closed, "audit"),
            recording(closed, "authn"),
            recording(closed, "dangerous"),
            recording(closed, "perf"),
            recording(closed, "vmtool"),
            recording(closed, "client"),
            recording(closed, "job"),
            recording(closed, "enhancement")
        );

        Assert.assertNotNull(closer);
        closer.close();
        closer.close();

        Assert.assertEquals(Arrays.asList("enhancement"), closed);
    }

    @Test
    public void fromOwnedResources_continuesAfterCloseFailure() throws Exception {
        List<String> closed = new ArrayList<>();
        ResourceOwnership ownership = new ResourceOwnership(true, true, false, false, false, false, false, true);

        AutoCloseable closer = ResourceCloser.fromOwnedResources(
            ownership,
            recording(closed, "audit"),
            throwingCloseable(closed, "authn"),
            recording(closed, "dangerous"),
            recording(closed, "perf"),
            recording(closed, "vmtool"),
            recording(closed, "client"),
            recording(closed, "job"),
            recording(closed, "enhancement")
        );

        Assert.assertNotNull(closer);
        closer.close();

        Assert.assertEquals(Arrays.asList("enhancement", "authn", "audit"), closed);
    }

    private static AutoCloseable recording(final List<String> closed, final String name) {
        return new AutoCloseable() {
            @Override
            public void close() {
                closed.add(name);
            }
        };
    }

    private static AutoCloseable throwingCloseable(final List<String> closed, final String name) {
        return new AutoCloseable() {
            @Override
            public void close() throws Exception {
                closed.add(name);
                throw new Exception("boom");
            }
        };
    }
}
