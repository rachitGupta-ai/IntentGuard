package com.intentguard.api;

import com.intentguard.domain.CorrectiveAction;

/**
 * Request body for resolving a pending {@code ask} Command_Event from the Control_Tower (Req 12.5).
 *
 * <p>The Administrator's choice is expressed as a {@link CorrectiveAction}: {@code ALLOW} confirms
 * the command so it may proceed, while {@code BLOCK} refuses it. {@code resolvedBy} optionally
 * identifies the acting Administrator for the recorded resolution; it defaults to {@code "admin"}
 * when absent.
 */
public record ResolveAskRequest(CorrectiveAction action, String resolvedBy) {
}
