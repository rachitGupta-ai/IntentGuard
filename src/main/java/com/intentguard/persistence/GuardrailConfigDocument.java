package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted, versioned guardrail configuration for the {@code guardrail_config} collection
 * (Req 3.1, Req 4). {@link #version} is monotonically increasing; the highest version is the
 * active configuration.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec. Enum-valued
 * fields (protected-target kinds) are stored as {@code name()} strings and timestamps as UTC
 * epoch millis, consistent with the other IntentGuard documents. Semantic validation and
 * hot-reload are layered on by {@code GuardrailConfigService} (Task 4.2); this document is the
 * storage shape only.
 */
public class GuardrailConfigDocument {

    private int version;
    private List<ProtectedTargetDocument> protectedTargets = new ArrayList<>();
    private int massOperationLimit;
    private List<String> destructiveVerbPatterns = new ArrayList<>();
    private double destructiveOperationFloor;
    private long dualControlConfirmationTimeoutMs;
    private Map<String, List<String>> capabilityScopes = new LinkedHashMap<>();
    private Map<String, Boolean> featureFlags = new LinkedHashMap<>();
    private String updatedBy;
    private long updatedAt;

    public GuardrailConfigDocument() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<ProtectedTargetDocument> getProtectedTargets() {
        return protectedTargets;
    }

    public void setProtectedTargets(List<ProtectedTargetDocument> protectedTargets) {
        this.protectedTargets = protectedTargets;
    }

    public int getMassOperationLimit() {
        return massOperationLimit;
    }

    public void setMassOperationLimit(int massOperationLimit) {
        this.massOperationLimit = massOperationLimit;
    }

    public List<String> getDestructiveVerbPatterns() {
        return destructiveVerbPatterns;
    }

    public void setDestructiveVerbPatterns(List<String> destructiveVerbPatterns) {
        this.destructiveVerbPatterns = destructiveVerbPatterns;
    }

    public double getDestructiveOperationFloor() {
        return destructiveOperationFloor;
    }

    public void setDestructiveOperationFloor(double destructiveOperationFloor) {
        this.destructiveOperationFloor = destructiveOperationFloor;
    }

    public long getDualControlConfirmationTimeoutMs() {
        return dualControlConfirmationTimeoutMs;
    }

    public void setDualControlConfirmationTimeoutMs(long dualControlConfirmationTimeoutMs) {
        this.dualControlConfirmationTimeoutMs = dualControlConfirmationTimeoutMs;
    }

    public Map<String, List<String>> getCapabilityScopes() {
        return capabilityScopes;
    }

    public void setCapabilityScopes(Map<String, List<String>> capabilityScopes) {
        this.capabilityScopes = capabilityScopes;
    }

    public Map<String, Boolean> getFeatureFlags() {
        return featureFlags;
    }

    public void setFeatureFlags(Map<String, Boolean> featureFlags) {
        this.featureFlags = featureFlags;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
