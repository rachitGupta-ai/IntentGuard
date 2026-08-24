package com.intentguard.assist;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Executes a shell command via ProcessBuilder, capturing stdout/stderr/exit code.
 * Applies a configurable execution timeout to prevent runaway processes.
 *
 * <p>If the process does not complete within the configured timeout
 * ({@code intentguard.assist.execution-timeout-ms}), it is forcibly destroyed
 * and an {@link ExecutionResult} with exit code {@code -1} is returned.
 */
@Component
public class CommandExecutor {

    private final long timeoutMs;

    public CommandExecutor(AssistProperties properties) {
        this.timeoutMs = properties.getExecutionTimeoutMs();
    }

    /**
     * Executes the given command as a shell process.
     *
     * @param command the shell command to execute
     * @param cwd     working directory for the process (may be {@code null} to inherit)
     * @return the execution result with stdout, stderr, and exit code
     */
    public ExecutionResult execute(String command, String cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            if (cwd != null) {
                pb.directory(new File(cwd));
            }
            Process process = pb.start();

            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new ExecutionResult(command, "", "Execution timed out", -1,
                        System.currentTimeMillis());
            }

            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            return new ExecutionResult(command, stdout, stderr, process.exitValue(),
                    System.currentTimeMillis());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionResult(command, "", "Execution error: " + e.getMessage(), -1,
                    System.currentTimeMillis());
        }
    }
}
