package com.intentguard.persistence;

/**
 * Persisted shape of a protected target embedded in a {@link GuardrailConfigDocument} (Req 3.1).
 *
 * <p>Mutable JavaBean with a no-arg constructor for the MongoDB POJO codec. The {@code kind} is
 * stored as the {@code TargetKind.name()} string.
 */
public class ProtectedTargetDocument {

    private String id;
    private String kind;
    private String matcher;
    private boolean blockOnAccess;

    public ProtectedTargetDocument() {
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

    public String getMatcher() {
        return matcher;
    }

    public void setMatcher(String matcher) {
        this.matcher = matcher;
    }

    public boolean isBlockOnAccess() {
        return blockOnAccess;
    }

    public void setBlockOnAccess(boolean blockOnAccess) {
        this.blockOnAccess = blockOnAccess;
    }
}
