package com.intentguard.persistence;

/**
 * Persisted form of a single divergence component's outcome, embedded in an
 * {@link AuditHistoryDocument} (Req 5.7). A {@code null} {@link #score} denotes an excluded
 * component, with {@link #note} carrying the exclusion reason.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor so the MongoDB POJO codec can map it.
 */
public class ComponentScoreDocument {

    private String id;
    private Double score;
    private double weight;
    private String note;

    public ComponentScoreDocument() {
    }

    public ComponentScoreDocument(String id, Double score, double weight, String note) {
        this.id = id;
        this.score = score;
        this.weight = weight;
        this.note = note;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
