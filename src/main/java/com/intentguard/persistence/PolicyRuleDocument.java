package com.intentguard.persistence;

/**
 * Persisted shape of a {@code PolicyRule} embedded in a {@link CommandPolicyDocument} (Req 2.1).
 *
 * <p>The {@code kind} ({@code PatternKind}) and {@code action} ({@code PolicyAction}) enums are
 * stored as their {@code name()} strings, and the optional {@code scope} is a nested
 * {@link PolicyScopeDocument}. Semantic validation (non-blank id, compilable pattern, non-null
 * action) lives on the {@code PolicyRule} domain record; this document is the storage shape only.
 *
 * <p>Mutable JavaBean with a no-arg constructor for the MongoDB POJO codec.
 */
public class PolicyRuleDocument {

    private String id;
    private String kind;
    private String pattern;
    private PolicyScopeDocument scope;
    private String action;

    public PolicyRuleDocument() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public PolicyScopeDocument getScope() {
        return scope;
    }

    public void setScope(PolicyScopeDocument scope) {
        this.scope = scope;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
