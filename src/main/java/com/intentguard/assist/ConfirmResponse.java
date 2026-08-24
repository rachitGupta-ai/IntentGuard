package com.intentguard.assist;

/**
 * Response body for POST /api/assist/confirm.
 *
 * @param sessionId   session identifier
 * @param command     the executed command text
 * @param stdout      captured standard output
 * @param stderr      captured standard error
 * @param exitCode    process exit code
 * @param success     whether exit code was 0
 * @param suggestion  follow-up suggestion (non-null when exitCode != 0)
 */
public record ConfirmResponse(
        String sessionId,
        String command,
        String stdout,
        String stderr,
        int exitCode,
        boolean success,
        String suggestion) {}
