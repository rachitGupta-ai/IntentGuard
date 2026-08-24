package com.intentguard.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedded timing-pattern statistics for a {@link BehavioralProfileDocument}: a 24-bucket
 * hour-of-day histogram and the mean inter-command interval in milliseconds.
 *
 * <p>Mutable JavaBean shape with a no-arg constructor for the MongoDB POJO codec.
 */
public class TimingPatternsDocument {

    private List<Integer> hourHistogram = new ArrayList<>();
    private long meanInterCommandMs;

    public TimingPatternsDocument() {
    }

    public TimingPatternsDocument(List<Integer> hourHistogram, long meanInterCommandMs) {
        this.hourHistogram = hourHistogram;
        this.meanInterCommandMs = meanInterCommandMs;
    }

    public List<Integer> getHourHistogram() {
        return hourHistogram;
    }

    public void setHourHistogram(List<Integer> hourHistogram) {
        this.hourHistogram = hourHistogram;
    }

    public long getMeanInterCommandMs() {
        return meanInterCommandMs;
    }

    public void setMeanInterCommandMs(long meanInterCommandMs) {
        this.meanInterCommandMs = meanInterCommandMs;
    }
}
