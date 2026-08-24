package com.intentguard.persistence;

/**
 * Persisted shape of a {@code PolicyScope} embedded in a {@link PolicyRuleDocument} (Req 2.1, 2.5).
 *
 * <p>Every facet is nullable; a {@code null} facet means "any". The {@code actorType} is stored as
 * the {@code ActorType.name()} string, consistent with the other IntentGuard documents.
 *
 * <p>Mutable JavaBean with a no-arg constructor for the MongoDB POJO codec.
 */
public class PolicyScopeDocument {

    private String user;
    private String group;
    private String repo;
    private String actorType;

    public PolicyScopeDocument() {
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getRepo() {
        return repo;
    }

    public void setRepo(String repo) {
        this.repo = repo;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }
}
