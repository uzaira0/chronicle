package com.openlattice.chronicle.collection.core;

import android.content.Context;
import android.content.ContextWrapper;

/**
 * Test-only stand-in for an Android {@link Context}.
 *
 * <p>The Phase 3 collection core never dereferences the {@link Context} it is handed in
 * pure JVM-testable paths — disabled modules and sinks ignore it entirely. JVM unit
 * tests cannot construct a real {@link Context}, so they pass {@link #stub()}.
 *
 * <p>{@link #stub()} returns a {@link ContextWrapper} built over a {@code null} base.
 * {@link ContextWrapper} is a concrete {@link Context} subclass with a trivial
 * constructor, so the instance is non-null — it satisfies the intrinsic null-check that
 * a Kotlin non-null {@code Context} parameter inserts. Every delegating method would
 * NPE on the {@code null} base, so any context-agnostic path that unexpectedly touched
 * the context fails loudly: the desired signal that the path belongs in an instrumented
 * test, not a JVM unit test.
 */
public final class TestContexts {

    private TestContexts() {
    }

    /**
     * A non-null {@link Context} stub for JVM unit tests of context-agnostic code
     * paths. Touching it (any delegating method) throws.
     */
    public static Context stub() {
        return new ContextWrapper(null);
    }
}
