package com.hellblazer.luciferase.lucien.balancing.fault;

/**
 * Subscription handle for unsubscribing from partition state change events.
 * <p>
 * This interface implements AutoCloseable to enable try-with-resources patterns
 * for automatic cleanup of event subscriptions.
 * <p>
 * Example usage:
 * <pre>{@code
 * try (var subscription = faultHandler.subscribeToChanges(event -> {
 *     log.info("Partition {} changed to {}", event.partitionId(), event.newStatus());
 * })) {
 *     // Process events...
 * } // Auto-unsubscribe on block exit
 * }</pre>
 */
public interface Subscription extends AutoCloseable {

    /**
     * Unsubscribe from events.
     * <p>
     * After calling this method, the associated consumer will no longer
     * receive partition state change events.
     * <p>
     * This method is idempotent - calling it multiple times has no effect
     * after the first call.
     */
    void unsubscribe();

    /**
     * Implements AutoCloseable by delegating to unsubscribe().
     * <p>
     * This allows Subscription to be used in try-with-resources blocks.
     *
     * @throws Exception never thrown, but required by AutoCloseable contract
     */
    @Override
    default void close() throws Exception {
        unsubscribe();
    }
}
