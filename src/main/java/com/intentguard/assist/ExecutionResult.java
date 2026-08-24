package com.intentguard.assist;

/**
 * The outcome of command execution.
 *
 * @param command   the executed command text
 * @param stdout    captured standard output
 * @param stderr    captured standard error
 * @param exitCode  process exit code
 * @param timestamp execution completion timestamp (UTC millis)
 */
public record ExecutionResult(
        String command,
        String stdout,
        String stderr,
        int exitCode,
        long timestamp) {}
