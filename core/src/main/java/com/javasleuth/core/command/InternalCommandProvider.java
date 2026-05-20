package com.javasleuth.core.command;

/**
 * Marker for command providers assembled by Java-Sleuth core.
 *
 * <p>This type is intentionally package-private so external plugin jars cannot opt into builtin
 * privileges by returning builtin metadata.</p>
 */
interface InternalCommandProvider {
}
