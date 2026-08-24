package com.intentguard.hardening;

import java.util.Objects;

/**
 * The outcome of probing a required guardrail dependency (for example the Datastore, the policy
 * store, or the LLM_Service) for availability within the guardrail decision budget.
 *
 * <p>A dependency is treated as <em>unavailable</em> — and therefore fail-closed — when it either
 * did not respond at all ({@code reachable == false}) or responded only after exceeding the
 * configured guardrail decision timeout ({@code elapsedMs > timeoutMs}). Modeling the outcome as a
 * plain value keeps {@link FailClosedGuard} a pure, deterministic function of its inputs so the
 * fail-closed property can be tested with a fixed clock and no real I/O.
 *
 * @param dependencyName the name of the required guardrail dependency that was probed
 * @param reachable      whether the dependency responded at all
 * @param elapsedMs      how long the probe took, in milliseconds (never negative)
 */
public record ProbeOutcome(String dependencyName, boolean reachable, long elapsedMs) {

    public ProbeOutcome {
        Objects.requireNonNull(dependencyName, "dependencyName must not be null");
        if (dependencyName.isBlank()) {
            throw new IllegalArgumentException("dependencyName must not be blank");
        }
        if (elapsedMs < 0) {
            throw new IllegalArgumentException("elapsedMs must not be negative: " + elapsedMs);
        }
    }

    /** A dependency that responded successfully within {@code elapsedMs}. */
    public static ProbeOutcome reachable(String dependencyName, long elapsedMs) {
        return new ProbeOutcome(dependencyName, true, elapsedMs);
    }

    /** A dependency that could not be reached at all. */
    public static ProbeOutcome unreachable(String dependencyName) {
        return new ProbeOutcome(dependencyName, false, 0L);
    }

    /**
     * Returns whether this outcome means the dependency was unavailable relative to
     * {@code timeoutMs}: either it was not reachable, or it responded only after exceeding the
     * timeout budget (Req 9.1).
     *
     * @param timeoutMs the configured guardrail decision timeout, in milliseconds
     * @return {@code true} when the dependency is unavailable and the engine must fail closed
     */
    public boolean isUnavailableWithin(long timeoutMs) {
        return !reachable || elapsedMs > timeoutMs;
    }
}
