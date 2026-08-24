/**
 * Signal_Ingestor module. Turns raw signals from the Shell_Hook (synchronous blocking gate
 * over a Unix domain socket) and the Audit_Feed (post-execution auditd tail) into normalized
 * {@code CommandEvent} objects, and correlates the two sources by user identity and timestamp
 * proximity.
 */
package com.intentguard.ingest;
