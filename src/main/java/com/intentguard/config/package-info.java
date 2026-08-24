/**
 * Threshold_Configuration domain model and hot-reload service (Req 7.1, 7.5).
 *
 * <p>Models versioned thresholds and component weights, validates that thresholds are ordered and
 * weights non-negative, rejects invalid updates while retaining the previously active
 * configuration, and applies valid updates to subsequent Command_Events without a restart.
 */
package com.intentguard.config;
