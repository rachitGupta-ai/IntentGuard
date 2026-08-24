package com.intentguard.persistence;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Wires the MongoDB Datastore as Spring beans configured from {@link MongoProperties}
 * ({@code intentguard.mongo.*}).
 *
 * <p>A POJO codec registry (with automatic mapping enabled) is installed on the client so the
 * plain-old-Java document classes in this package round-trip without hand-written codecs.
 *
 * <p><strong>Resilience:</strong> {@link MongoClients#create(MongoClientSettings)} and
 * {@link MongoClient#getDatabase(String)} do not open a network connection; the driver connects
 * lazily on the first database operation. This means the beans construct successfully even when
 * no MongoDB is reachable, and the repositories degrade gracefully (see the last-known-good
 * caching in {@link BehavioralProfileRepository} and {@link ThresholdConfigRepository}).
 */
@Configuration
@EnableConfigurationProperties(MongoProperties.class)
public class MongoConfig {

    /**
     * A codec registry combining the driver defaults with an automatic POJO codec provider so the
     * document classes in this package (and their nested/enum fields) serialize without bespoke
     * codecs.
     */
    @Bean
    public CodecRegistry intentGuardCodecRegistry() {
        return fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                fromProviders(PojoCodecProvider.builder().automatic(true).build()));
    }

    /**
     * The shared {@code MongoClient}. Construction is lazy with respect to the network: no
     * connection is opened until the first operation, keeping application startup resilient when
     * the Datastore is temporarily unavailable.
     */
    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(MongoProperties properties, CodecRegistry codecRegistry) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(properties.getConnectionString()))
                .codecRegistry(codecRegistry)
                .build();
        return MongoClients.create(settings);
    }

    /** The IntentGuard database handle used by the repositories. */
    @Bean
    public MongoDatabase mongoDatabase(MongoClient mongoClient, MongoProperties properties) {
        return mongoClient.getDatabase(properties.getDatabase());
    }
}
