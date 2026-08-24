package com.intentguard.blastradius;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.intentguard.persistence.GuardrailConfigDocument;
import com.intentguard.persistence.ProtectedTargetDocument;

/**
 * Immutable, versioned, hot-reloadable configuration carrying the blast-radius / protected-target
 * and dual-control tunables consumed by the guardrail layer (Req 3, Req 4.5, Req 4.8).
 *
 * <p>Mirrors {@link com.intentguard.config.ThresholdConfiguration}: every instance is valid by
 * construction (the compact constructor enforces the invariants below and throws
 * {@link InvalidGuardrailConfigException} otherwise), and {@link #toDocument()} /
 * {@link #fromDocument(GuardrailConfigDocument)} bridge the persisted shape.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code version} is at least 1 (monotonically increasing across updates);</li>
 *   <li>{@code massOperationLimit} is a positive integer (default {@value #DEFAULT_MASS_OPERATION_LIMIT});</li>
 *   <li>{@code destructiveOperationFloor} lies in [0.0, 1.0] (default {@value #DEFAULT_DESTRUCTIVE_OPERATION_FLOOR});</li>
 *   <li>{@code dualControlConfirmationTimeoutMs} is strictly positive;</li>
 *   <li>protected-target ids are unique.</li>
 * </ul>
 *
 * @param protectedTargets                  the configured sensitive paths/hosts/resources (Req 3.1)
 * @param massOperationLimit                affected-item count above which the floor is raised (Req 3.5)
 * @param destructiveVerbPatterns           patterns marking irreversible operations (Req 3.6)
 * @param destructiveOperationFloor         Divergence_Score floor for destructive verbs (Req 3.6)
 * @param dualControlConfirmationTimeoutMs  dual-control confirmation timeout (Req 4.5)
 * @param capabilityScopes                  per-agent permitted command-class subsets (Req 4.8)
 * @param featureFlags                      per-guardrail enable flags for the stretch guardrails
 */
public record GuardrailConfig(
        int version,
        List<ProtectedTarget> protectedTargets,
        int massOperationLimit,
        List<String> destructiveVerbPatterns,
        double destructiveOperationFloor,
        long dualControlConfirmationTimeoutMs,
        Map<String, List<String>> capabilityScopes,
        Map<String, Boolean> featureFlags,
        String updatedBy,
        long updatedAt) {

    /** Default mass-operation limit: 100 affected items (Req 3.5). */
    public static final int DEFAULT_MASS_OPERATION_LIMIT = 100;

    /** Default destructive-operation Divergence_Score floor: 0.90 (Req 3.6). */
    public static final double DEFAULT_DESTRUCTIVE_OPERATION_FLOOR = 0.90;

    /** Default dual-control confirmation timeout: 5 minutes (Req 4.5). */
    public static final long DEFAULT_DUAL_CONTROL_CONFIRMATION_TIMEOUT_MS = 300_000L;

    public GuardrailConfig {
        if (version < 1) {
            throw new InvalidGuardrailConfigException("version must be >= 1: " + version);
        }
        if (massOperationLimit <= 0) {
            throw new InvalidGuardrailConfigException(
                    "massOperationLimit must be a positive integer: " + massOperationLimit);
        }
        if (Double.isNaN(destructiveOperationFloor)
                || destructiveOperationFloor < 0.0
                || destructiveOperationFloor > 1.0) {
            throw new InvalidGuardrailConfigException(
                    "destructiveOperationFloor must be in [0.0, 1.0]: " + destructiveOperationFloor);
        }
        if (dualControlConfirmationTimeoutMs <= 0) {
            throw new InvalidGuardrailConfigException(
                    "dualControlConfirmationTimeoutMs must be positive: "
                            + dualControlConfirmationTimeoutMs);
        }

        // Defensive, unmodifiable copies with null-element / uniqueness checks.
        List<ProtectedTarget> targets = new ArrayList<>();
        Map<String, Boolean> seenIds = new LinkedHashMap<>();
        if (protectedTargets != null) {
            for (ProtectedTarget target : protectedTargets) {
                if (target == null) {
                    throw new InvalidGuardrailConfigException("protectedTargets contains a null entry");
                }
                if (seenIds.put(target.id(), Boolean.TRUE) != null) {
                    throw new InvalidGuardrailConfigException(
                            "duplicate ProtectedTarget id: " + target.id());
                }
                targets.add(target);
            }
        }
        protectedTargets = List.copyOf(targets);

        List<String> verbs = new ArrayList<>();
        if (destructiveVerbPatterns != null) {
            for (String pattern : destructiveVerbPatterns) {
                if (pattern == null || pattern.isBlank()) {
                    throw new InvalidGuardrailConfigException(
                            "destructiveVerbPatterns contains a null or blank pattern");
                }
                verbs.add(pattern);
            }
        }
        destructiveVerbPatterns = List.copyOf(verbs);

        Map<String, List<String>> scopes = new LinkedHashMap<>();
        if (capabilityScopes != null) {
            capabilityScopes.forEach((agent, caps) -> {
                if (agent == null || agent.isBlank()) {
                    throw new InvalidGuardrailConfigException(
                            "capabilityScopes contains a null or blank agent id");
                }
                scopes.put(agent, caps == null ? List.of() : List.copyOf(caps));
            });
        }
        capabilityScopes = Map.copyOf(scopes);

        featureFlags = featureFlags == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(featureFlags));

        Objects.requireNonNull(updatedBy, "updatedBy must not be null");
    }

    /**
     * A baseline configuration at version 1 with the documented defaults and no protected targets,
     * useful as a first-run seed before any Administrator update.
     */
    public static GuardrailConfig defaults(String updatedBy, long updatedAt) {
        return new GuardrailConfig(
                1,
                List.of(),
                DEFAULT_MASS_OPERATION_LIMIT,
                List.of(),
                DEFAULT_DESTRUCTIVE_OPERATION_FLOOR,
                DEFAULT_DUAL_CONTROL_CONFIRMATION_TIMEOUT_MS,
                Map.of(),
                Map.of(),
                updatedBy,
                updatedAt);
    }

    /** Reconstructs a {@link GuardrailConfig} from its persisted {@link GuardrailConfigDocument}. */
    public static GuardrailConfig fromDocument(GuardrailConfigDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        List<ProtectedTarget> targets = new ArrayList<>();
        if (document.getProtectedTargets() != null) {
            for (ProtectedTargetDocument td : document.getProtectedTargets()) {
                targets.add(new ProtectedTarget(
                        td.getId(), parseTargetKind(td.getKind()), td.getMatcher(), td.isBlockOnAccess()));
            }
        }
        Map<String, List<String>> scopes = new LinkedHashMap<>();
        if (document.getCapabilityScopes() != null) {
            document.getCapabilityScopes().forEach((agent, caps) ->
                    scopes.put(agent, caps == null ? List.of() : new ArrayList<>(caps)));
        }
        return new GuardrailConfig(
                document.getVersion(),
                targets,
                document.getMassOperationLimit(),
                document.getDestructiveVerbPatterns() == null
                        ? List.of()
                        : new ArrayList<>(document.getDestructiveVerbPatterns()),
                document.getDestructiveOperationFloor(),
                document.getDualControlConfirmationTimeoutMs(),
                scopes,
                document.getFeatureFlags() == null
                        ? Map.of()
                        : new LinkedHashMap<>(document.getFeatureFlags()),
                document.getUpdatedBy(),
                document.getUpdatedAt());
    }

    private static TargetKind parseTargetKind(String key) {
        try {
            return TargetKind.valueOf(key);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidGuardrailConfigException("unknown protected-target kind: " + key);
        }
    }

    /** Converts this configuration to its persisted {@link GuardrailConfigDocument} shape. */
    public GuardrailConfigDocument toDocument() {
        GuardrailConfigDocument document = new GuardrailConfigDocument();
        document.setVersion(version);
        List<ProtectedTargetDocument> targetDocs = new ArrayList<>();
        for (ProtectedTarget target : protectedTargets) {
            ProtectedTargetDocument td = new ProtectedTargetDocument();
            td.setId(target.id());
            td.setKind(target.kind().name());
            td.setMatcher(target.matcher());
            td.setBlockOnAccess(target.blockOnAccess());
            targetDocs.add(td);
        }
        document.setProtectedTargets(targetDocs);
        document.setMassOperationLimit(massOperationLimit);
        document.setDestructiveVerbPatterns(new ArrayList<>(destructiveVerbPatterns));
        document.setDestructiveOperationFloor(destructiveOperationFloor);
        document.setDualControlConfirmationTimeoutMs(dualControlConfirmationTimeoutMs);
        Map<String, List<String>> scopes = new LinkedHashMap<>();
        capabilityScopes.forEach((agent, caps) -> scopes.put(agent, new ArrayList<>(caps)));
        document.setCapabilityScopes(scopes);
        document.setFeatureFlags(new LinkedHashMap<>(featureFlags));
        document.setUpdatedBy(updatedBy);
        document.setUpdatedAt(updatedAt);
        return document;
    }
}
