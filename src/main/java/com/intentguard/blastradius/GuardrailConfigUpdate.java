package com.intentguard.blastradius;

import java.util.List;
import java.util.Map;

/**
 * A proposed Administrator update to the {@link GuardrailConfig} (Req 3.1, 9.5, 9.6).
 *
 * <p>Carries only the tunable fields; the {@code version}, author, and timestamp are assigned by
 * {@link GuardrailConfigService} when a valid update is applied. The service validates a proposed
 * update by materializing a {@link GuardrailConfig} from it — an invalid proposal is rejected
 * (an {@link InvalidGuardrailConfigException} is thrown) and the previously active configuration is
 * retained.
 *
 * @param protectedTargets                  configured sensitive paths/hosts/resources (Req 3.1)
 * @param massOperationLimit                affected-item count above which the floor is raised (Req 3.5)
 * @param destructiveVerbPatterns           patterns marking irreversible operations (Req 3.6)
 * @param destructiveOperationFloor         Divergence_Score floor for destructive verbs (Req 3.6)
 * @param dualControlConfirmationTimeoutMs  dual-control confirmation timeout (Req 4.5)
 * @param capabilityScopes                  per-agent permitted command-class subsets (Req 4.8)
 * @param featureFlags                      per-guardrail enable flags for the stretch guardrails
 */
public record GuardrailConfigUpdate(
        List<ProtectedTarget> protectedTargets,
        int massOperationLimit,
        List<String> destructiveVerbPatterns,
        double destructiveOperationFloor,
        long dualControlConfirmationTimeoutMs,
        Map<String, List<String>> capabilityScopes,
        Map<String, Boolean> featureFlags) {

    /** Derives an update carrying the tunable fields of an existing configuration. */
    public static GuardrailConfigUpdate from(GuardrailConfig config) {
        return new GuardrailConfigUpdate(
                config.protectedTargets(),
                config.massOperationLimit(),
                config.destructiveVerbPatterns(),
                config.destructiveOperationFloor(),
                config.dualControlConfirmationTimeoutMs(),
                config.capabilityScopes(),
                config.featureFlags());
    }

    /**
     * Materializes a validated {@link GuardrailConfig} from this proposed update at the given
     * version, stamping the author and timestamp. Throws {@link InvalidGuardrailConfigException}
     * if the proposed values are invalid (validation happens in the {@code GuardrailConfig}
     * compact constructor).
     */
    public GuardrailConfig toConfig(int version, String updatedBy, long updatedAt) {
        return new GuardrailConfig(
                version,
                protectedTargets,
                massOperationLimit,
                destructiveVerbPatterns,
                destructiveOperationFloor,
                dualControlConfirmationTimeoutMs,
                capabilityScopes,
                featureFlags,
                updatedBy,
                updatedAt);
    }
}
