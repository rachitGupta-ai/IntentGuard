package com.intentguard.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** Unit tests for the deterministic command reduction used by the scoring components. */
class CommandNormalizerTest {

    @Test
    void executableIsFirstTokenBasenameLowercased() {
        assertThat(CommandNormalizer.executable("git status")).isEqualTo("git");
        assertThat(CommandNormalizer.executable("/usr/bin/GIT status")).isEqualTo("git");
        assertThat(CommandNormalizer.executable("  ls -la  ")).isEqualTo("ls");
    }

    @Test
    void executableOfBlankIsEmpty() {
        assertThat(CommandNormalizer.executable("")).isEmpty();
        assertThat(CommandNormalizer.executable("   ")).isEmpty();
        assertThat(CommandNormalizer.executable(null)).isEmpty();
    }

    @Test
    void normalizedTokenFoldsSubcommandForKnownExecutables() {
        assertThat(CommandNormalizer.normalizedToken("git commit -m x")).isEqualTo("git commit");
        assertThat(CommandNormalizer.normalizedToken("git push origin main")).isEqualTo("git push");
        assertThat(CommandNormalizer.normalizedToken("kubectl apply -f x.yaml")).isEqualTo("kubectl apply");
    }

    @Test
    void normalizedTokenIgnoresFlagArgumentsAndPlainExecutables() {
        assertThat(CommandNormalizer.normalizedToken("git -v")).isEqualTo("git");
        assertThat(CommandNormalizer.normalizedToken("ls -la")).isEqualTo("ls");
        assertThat(CommandNormalizer.normalizedToken("curl https://x")).isEqualTo("curl");
    }

    @Test
    void categoryMapsKnownExecutablesAndFallsBackToOther() {
        assertThat(CommandNormalizer.category("git commit")).isEqualTo("vcs");
        assertThat(CommandNormalizer.category("curl https://x")).isEqualTo("network");
        assertThat(CommandNormalizer.category("kubectl get pods")).isEqualTo("orchestration");
        assertThat(CommandNormalizer.category("someunknownbinary --flag")).isEqualTo(CommandNormalizer.CATEGORY_OTHER);
    }

    @Test
    void reductionIsDeterministic() {
        assertThat(CommandNormalizer.normalizedToken("git   commit  -m  x"))
                .isEqualTo(CommandNormalizer.normalizedToken("git commit -m x"));
        assertThat(CommandNormalizer.category("GIT status")).isEqualTo(CommandNormalizer.category("git status"));
    }
}
