package com.intentguard.blastradius;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

import com.intentguard.persistence.GuardrailConfigDocument;
import com.intentguard.persistence.GuardrailConfigRepository;

/**
 * Unit tests for {@link GuardrailConfigService}: the active configuration is loaded on startup,
 * valid updates are applied (version incremented and persisted), invalid updates are rejected while
 * the previous configuration is retained (last-known-good) and nothing is persisted, and a
 * hot-reloaded configuration takes effect without a restart (Req 3.1, 9.5, 9.6). The repository is
 * mocked so no live Datastore is required.
 */
class GuardrailConfigServiceTest {

    private GuardrailConfigRepository repository;
    private GuardrailConfigService service;

    private static ProtectedTarget sshKeys() {
        return new ProtectedTarget("ssh-keys", TargetKind.PATH, "~/.ssh/**", true);
    }

    private static GuardrailConfigUpdate validUpdate(int massOperationLimit, double floor) {
        return new GuardrailConfigUpdate(
                List.of(sshKeys()),
                massOperationLimit,
                List.of("rm -rf", "DROP TABLE"),
                floor,
                300_000L,
                Map.of("agent-a", List.of("read", "write")),
                Map.of("blastRadiusGuard", Boolean.TRUE));
    }

    @BeforeEach
    void setUp() {
        repository = mock(GuardrailConfigRepository.class);
        Clock fixedClock = Clock.fixed(Instant.ofEpochMilli(12345L), ZoneOffset.UTC);
        service = new GuardrailConfigService(repository);
        service.setClock(fixedClock);
    }

    @Test
    void loadActiveReadsFromRepositoryOnStartup() {
        GuardrailConfigDocument document = new GuardrailConfig(
                7, List.of(sshKeys()), 50, List.of("rm -rf"), 0.9, 300_000L,
                Map.of(), Map.of(), "admin", 1L).toDocument();
        when(repository.findActive()).thenReturn(Optional.of(document));

        service.loadActive();

        assertThat(service.getActiveConfig()).isPresent();
        assertThat(service.getActiveConfig().orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void firstValidUpdateStartsAtVersionOneAndPersists() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();

        GuardrailConfig applied = service.applyUpdate(validUpdate(50, 0.9), "admin");

        assertThat(applied.version()).isEqualTo(1);
        assertThat(applied.updatedBy()).isEqualTo("admin");
        assertThat(applied.updatedAt()).isEqualTo(12345L);
        assertThat(service.getActiveConfig()).contains(applied);
        verify(repository).save(any(GuardrailConfigDocument.class));
    }

    @Test
    void validUpdateIncrementsVersionAndTakesEffectForSubsequentReads() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();

        service.applyUpdate(validUpdate(50, 0.9), "admin");
        GuardrailConfig second = service.applyUpdate(validUpdate(25, 0.75), "admin");

        assertThat(second.version()).isEqualTo(2);
        // Subsequent guardrail evaluations would read this new active config without a restart.
        assertThat(service.getActiveConfig().orElseThrow().massOperationLimit()).isEqualTo(25);
        assertThat(service.getActiveConfig().orElseThrow().destructiveOperationFloor()).isEqualTo(0.75);
    }

    @Test
    void invalidUpdateIsRejectedAndPreviousConfigRetained() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();
        GuardrailConfig good = service.applyUpdate(validUpdate(50, 0.9), "admin");

        // A destructive-operation floor above 1.0 is invalid.
        GuardrailConfigUpdate invalid = validUpdate(50, 1.5);

        assertThatThrownBy(() -> service.applyUpdate(invalid, "attacker"))
                .isInstanceOf(InvalidGuardrailConfigException.class);

        // Previous configuration is retained unchanged (last-known-good).
        assertThat(service.getActiveConfig()).contains(good);
        assertThat(service.getActiveConfig().orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void invalidUpdateDoesNotPersist() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();

        // A non-positive mass-operation limit is invalid.
        GuardrailConfigUpdate invalidLimit = validUpdate(0, 0.9);

        assertThatThrownBy(() -> service.applyUpdate(invalidLimit, "admin"))
                .isInstanceOf(InvalidGuardrailConfigException.class);

        verify(repository, never()).save(any());
        assertThat(service.getActiveConfig()).isEmpty();
    }

    @Test
    void reloadFromDatastoreSwapsInNewerVersion() {
        when(repository.findActive()).thenReturn(Optional.empty());
        service.loadActive();
        service.applyUpdate(validUpdate(50, 0.9), "admin"); // version 1 active

        GuardrailConfigDocument newer = new GuardrailConfig(
                5, List.of(sshKeys()), 10, List.of("rm -rf"), 0.5, 300_000L,
                Map.of(), Map.of(), "other-admin", 2L).toDocument();
        when(repository.findActive()).thenReturn(Optional.of(newer));

        Optional<GuardrailConfig> reloaded = service.reloadFromDatastore();

        assertThat(reloaded).isPresent();
        assertThat(reloaded.orElseThrow().version()).isEqualTo(5);
        assertThat(service.getActiveConfig().orElseThrow().massOperationLimit()).isEqualTo(10);
    }

    @Test
    void initializeSeedsDefaultWhenDatastoreEmptyAndPersists() {
        when(repository.findActive()).thenReturn(Optional.empty());

        GuardrailConfig defaults = GuardrailConfig.defaults("system", 100L);
        service.initialize(defaults);

        assertThat(service.getActiveConfig()).contains(defaults);
        verify(repository).save(any(GuardrailConfigDocument.class));
    }

    @Test
    void initializeLoadsExistingConfigInsteadOfSeeding() {
        GuardrailConfigDocument existing = new GuardrailConfig(
                3, List.of(), 50, List.of(), 0.9, 300_000L, Map.of(), Map.of(), "admin", 1L)
                .toDocument();
        when(repository.findActive()).thenReturn(Optional.of(existing));

        service.initialize(GuardrailConfig.defaults("system", 100L));

        assertThat(service.getActiveConfig().orElseThrow().version()).isEqualTo(3);
        verify(repository, never()).save(any());
    }

    @Test
    void initializeIsResilientToDatastoreOutage() {
        when(repository.findActive()).thenThrow(new RuntimeException("datastore down"));

        GuardrailConfig defaults = GuardrailConfig.defaults("system", 100L);
        service.initialize(defaults);

        // The default is held in memory so the guardrail layer can still function.
        assertThat(service.getActiveConfig()).contains(defaults);
    }

    @Test
    void loadActiveIsResilientToDatastoreOutage() {
        when(repository.findActive()).thenThrow(new RuntimeException("datastore down"));

        service.loadActive();

        assertThat(service.getActiveConfig()).isEmpty();
    }
}
