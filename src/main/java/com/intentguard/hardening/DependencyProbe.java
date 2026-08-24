package com.intentguard.hardening;

/**
 * A check that reports whether a required guardrail dependency is reachable within the guardrail
 * decision budget. Implementations perform the actual reachability test (a Datastore ping, a policy
 * store read, an LLM_Service health check) and return a {@link ProbeOutcome} describing the result.
 *
 * <p>Modeled as a functional interface so callers can inject a real probe in production and a
 * deterministic fake in tests, keeping {@link FailClosedGuard} pure and reproducible.
 */
@FunctionalInterface
public interface DependencyProbe {

    /**
     * Probes the required guardrail dependency for availability.
     *
     * @return the probe outcome (dependency name, reachability, and elapsed time)
     */
    ProbeOutcome probe();
}
