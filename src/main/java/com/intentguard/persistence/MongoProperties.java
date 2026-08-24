package com.intentguard.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code intentguard.mongo.*} configuration (see {@code application.yml}) that the
 * {@link MongoConfig} uses to construct the {@code MongoClient} / {@code MongoDatabase} beans for
 * the Datastore.
 *
 * <p>Only the connection string and database name are configuration; credentials, when present,
 * are carried inside the connection string (which itself is sourced from configuration/secret
 * material rather than committed).
 */
@ConfigurationProperties(prefix = "intentguard.mongo")
public class MongoProperties {

    /** MongoDB connection string, e.g. {@code mongodb://localhost:27017}. */
    private String connectionString = "mongodb://localhost:27017";

    /** Logical database holding the IntentGuard collections. */
    private String database = "intentguard";

    public String getConnectionString() {
        return connectionString;
    }

    public void setConnectionString(String connectionString) {
        this.connectionString = connectionString;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }
}
