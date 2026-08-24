package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted, versioned CommandPolicy for the {@code command_policies} collection (Req 2.14).
 * {@link #version} is monotonically increasing; the highest version is the active policy.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec. The ordered
 * {@link PolicyRuleDocument} entries embed their enum fields as {@code name()} strings and their
 * optional scope as a nested {@link PolicyScopeDocument}; {@link #updatedAt} is UTC epoch millis.
 * These conventions match the other IntentGuard documents so the policy round-trips without
 * hand-written codecs. Semantic validation (version {@code >= 1}, unique rule ids, compilable
 * patterns) lives on the {@code CommandPolicy} domain record; this document is the storage shape
 * only.
 */
public class CommandPolicyDocument {

    private int version;
    private List<PolicyRuleDocument> rules = new ArrayList<>();
    private String updatedBy;
    private long updatedAt;

    public CommandPolicyDocument() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<PolicyRuleDocument> getRules() {
        return rules;
    }

    public void setRules(List<PolicyRuleDocument> rules) {
        this.rules = rules;
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
