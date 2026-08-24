package com.intentguard.persistence;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.ReplaceOptions;

/**
 * Repository for the {@code scenario_baselines} collection (Req 16.1, 16.2). Baselines are keyed
 * by {@code scenarioId} and upserted; replaying a scenario loads its frozen seed profile,
 * thresholds, and scripted events.
 */
@Repository
public class ScenarioBaselineRepository {

    static final String COLLECTION = "scenario_baselines";

    private final MongoCollection<ScenarioBaselineDocument> collection;

    public ScenarioBaselineRepository(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION, ScenarioBaselineDocument.class);
    }

    /** Upserts a scenario baseline by {@code scenarioId}. */
    public void save(ScenarioBaselineDocument baseline) {
        collection.replaceOne(
                eq("scenarioId", baseline.getScenarioId()),
                baseline,
                new ReplaceOptions().upsert(true));
    }

    /** Loads a scenario baseline by id. */
    public Optional<ScenarioBaselineDocument> findByScenarioId(String scenarioId) {
        return Optional.ofNullable(collection.find(eq("scenarioId", scenarioId)).first());
    }
}
