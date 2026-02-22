package com.javasleuth.foundation.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class WildcardMatcherTest {
    @Test
    public void matchesSupportsStarWildcard() {
        assertTrue(WildcardMatcher.matches("com.example.Foo", "com.example.*"));
        assertTrue(WildcardMatcher.matches("FooServiceImpl", "*Service*"));
        assertTrue(WildcardMatcher.matches("Foo", "*"));
        assertFalse(WildcardMatcher.matches("Foo", "Bar*"));
        assertFalse(WildcardMatcher.matches("Foo", "FooBar"));
        assertTrue(WildcardMatcher.matches("Foo", "Foo"));
    }
}
