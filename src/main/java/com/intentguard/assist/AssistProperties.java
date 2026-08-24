package com.intentguard.assist;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Binds the {@code intentguard.assist.*} configuration (see {@code application.yml}) used by
 * the NL Operations Assistant components.
 *
 * <p>Provides session lifecycle, rate-limiting, execution timeout, and safety blocklist settings.
 * All numeric values must be at least 1; the blocklist must not be null (but may be empty if the
 * deployer explicitly chooses to disable blocklist filtering).
 */
@Validated
@ConfigurationProperties(prefix = "intentguard.assist")
public class AssistProperties {

    /** Session idle timeout in milliseconds. Default: 300000 (5 minutes). */
    @Min(1)
    private long sessionTimeoutMs = 300_000;

    /** Maximum queries per operator per minute. Default: 10. */
    @Min(1)
    private int rateLimitPerMinute = 10;

    /** Command execution timeout in milliseconds. Default: 30000 (30 seconds). */
    @Min(1)
    private long executionTimeoutMs = 30_000;

    /**
     * Regex patterns for the generation blocklist. Any generated command matching one of these
     * patterns is silently removed before being presented to the operator (Req 3.5).
     */
    @NotNull
    private List<String> blocklist = List.of(
            "rm\\s+-rf\\s+/(?:\\s|$)",
            "mkfs",
            "rmmod",
            "modprobe\\s+-r"
    );

    public long getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public void setSessionTimeoutMs(long sessionTimeoutMs) {
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public long getExecutionTimeoutMs() {
        return executionTimeoutMs;
    }

    public void setExecutionTimeoutMs(long executionTimeoutMs) {
        this.executionTimeoutMs = executionTimeoutMs;
    }

    public List<String> getBlocklist() {
        return blocklist;
    }

    public void setBlocklist(List<String> blocklist) {
        this.blocklist = blocklist;
    }
}
