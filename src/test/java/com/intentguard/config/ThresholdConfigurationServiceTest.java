package com.intentguard.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intentguard.domain.ComponentId;
import com.intentguard.persistence.ThresholdConfigDocument;
import com.intentguard.persistence.ThresholdConfigRepository;

/**
 * Unit tests for {@link ThresholdConfigurationService}: valid updates are applied (version
 * incremented and persisted), invalid updates are rejected while the previous configuration is
 * retained, and hot-reloaded configuration takes effect without restart (Req 7.1, 7.5). The
 * repository is mocked so no live Datastore is required.
 */
class ThresholdConfigurationServiceTest {

    private ThresholdConfigRepository repository;
    private ThresholdConfigurationService service;

    private static Map<ComponentId, Double> validWeights() {
        Map<ComponentId, Double> weights = new EnumMap<>(ComponentId.class);
        weights.put(ComponentId.SEQUENCE_SURPRISE, 0.25);
        weights.put(ComponentId.CONTEXT_MISMATCH, 0.20);
        weights.put(ComponentId.BEHAVIORAL_DEVIATION, 0.25);
        weights.put(ComponentId.SEMANTIC_INCONSISTENCY, 0.30);
        return weights;
    }

    private static ThresholdConfigUpdate validUpdate(double ask, double block) {
        return new ThresholdConfigUpdate(ask, block, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000);
    }

    @BeforeEach
    void setUp() {
        repository = mock(ThresholdConfigRepository.class);
        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(12345L), ZoneOffset.UTC);
        service = new ThresholdConfigurationService(repository);
        service.setClock(fixedClock);
    }

    @Test
    void loadActiveReadsFromRepositoryOnStartup() {
        ThresholdConfigDocument document = new ThresholdConfiguration(
                7, 0.4, 0.7, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "admin", 1L).toDocument();
        when(repository.findActive()).thenReturn(Optional.of(document));

        service.loadActive();

        assertThat(service.getActiveConfig()).isPresent();
        assertThat(service.getActiveConfig().orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void firstValidUpdateStartsAtVersionOneAndPersists() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();

        ThresholdConfiguration applied = service.applyUpdate(validUpdate(0.4, 0.7), "admin");

        assertThat(applied.version()).isEqualTo(1);
        assertThat(applied.updatedBy()).isEqualTo("admin");
        assertThat(applied.updatedAt()).isEqualTo(12345L);
        assertThat(service.getActiveConfig()).contains(applied);
        verify(repository).save(any(ThresholdConfigDocument.class));
    }

    @Test
    void validUpdateIncrementsVersionAndTakesEffectForSubsequentReads() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();

        service.applyUpdate(validUpdate(0.4, 0.7), "admin");
        ThresholdConfiguration second = service.applyUpdate(validUpdate(0.3, 0.6), "admin");

        assertThat(second.version()).isEqualTo(2);
        // Subsequent Command_Events would read this new active config without a restart.
        assertThat(service.getActiveConfig().orElseThrow().askThreshold()).isEqualTo(0.3);
        assertThat(service.getActiveConfig().orElseThrow().blockThreshold()).isEqualTo(0.6);
    }

    @Test
    void invalidUpdateIsRejectedAndPreviousConfigRetained() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();
        ThresholdConfiguration good = service.applyUpdate(validUpdate(0.4, 0.7), "admin");

        // Ask threshold above block threshold is invalid.
        ThresholdConfigUpdate invalid = validUpdate(0.9, 0.5);

        assertThatThrownBy(() -> service.applyUpdate(invalid, "attacker"))
                .isInstanceOf(InvalidThresholdConfigException.class);

        // Previous configuration is retained unchanged.
        assertThat(service.getActiveConfig()).contains(good);
        assertThat(service.getActiveConfig().orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void invalidUpdateDoesNotPersist() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();

        ThresholdConfigUpdate invalidNegativeWeight = new ThresholdConfigUpdate(
                0.4, 0.7, Map.of(ComponentId.CONTEXT_MISMATCH, -1.0), 0.15, 200, 5000, 15000, 1200, 1000);

        assertThatThrownBy(() -> service.applyUpdate(invalidNegativeWeight, "admin"))
                .isInstanceOf(InvalidThresholdConfigException.class);

        verify(repository, never()).save(any());
        assertThat(service.getActiveConfig()).isEmpty();
    }

    @Test
    void reloadFromDatastoreSwapsInNewerVersion() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();
        service.applyUpdate(validUpdate(0.4, 0.7), "admin"); // version 1 active

        ThresholdConfigDocument newer = new ThresholdConfiguration(
                5, 0.2, 0.5, validWeights(), 0.15, 200, 5000, 15000, 1200, 1000, "other-admin", 2L)
                .toDocument();
        when(repository.findActive()).thenReturn(Optional.of(newer));

        Optional<ThresholdConfiguration> reloaded = service.reloadFromDatastore();

        assertThat(reloaded).isPresent();
        assertThat(reloaded.orElseThrow().version()).isEqualTo(5);
        assertThat(service.getActiveConfig().orElseThrow().askThreshold()).isEqualTo(0.2);
    }
}
