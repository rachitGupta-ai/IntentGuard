package com.intentguard.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

/**
 * Smoke test asserting the concrete {@link TranslationProvider} adapters are configured to use
 * encrypted (HTTPS) transport endpoints only (Req 11.1).
 *
 * <p>Lives in {@code com.intentguard.translation} so it can reach the package-private
 * {@link AbstractHttpTranslationProvider#endpoint()} and
 * {@link AbstractHttpTranslationProvider#usesEncryptedTransport()} accessors, the static
 * {@link AbstractHttpTranslationProvider#requireEncrypted(String)} guard, and the package-private
 * test constructors that accept a custom endpoint.
 */
class EncryptedTransportSmokeTest {

    private final TranslationProperties properties = new TranslationProperties();

    @Test
    void bhashiniDefaultEndpointUsesEncryptedTransport() {
        BhashiniTranslationProvider provider = new BhashiniTranslationProvider(properties);

        assertThat(provider.endpoint().getScheme()).isEqualTo("https");
        assertThat(provider.usesEncryptedTransport()).isTrue();
        assertThat(BhashiniTranslationProvider.DEFAULT_ENDPOINT).startsWith("https://");
    }

    @Test
    void cloudDefaultEndpointUsesEncryptedTransport() {
        CloudTranslationProvider provider = new CloudTranslationProvider(properties);

        assertThat(provider.endpoint().getScheme()).isEqualTo("https");
        assertThat(provider.usesEncryptedTransport()).isTrue();
        assertThat(CloudTranslationProvider.DEFAULT_ENDPOINT).startsWith("https://");
    }

    @Test
    void bhashiniRejectsNonEncryptedEndpoint() {
        assertThatThrownBy(() ->
                new BhashiniTranslationProvider(properties, "http://insecure.example/translate", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encrypted transport");
    }

    @Test
    void cloudRejectsNonEncryptedEndpoint() {
        assertThatThrownBy(() ->
                new CloudTranslationProvider(properties, "http://insecure.example/translate", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encrypted transport");
    }

    @Test
    void requireEncryptedRejectsNonHttpsSchemes() {
        assertThatThrownBy(() -> AbstractHttpTranslationProvider.requireEncrypted("http://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbstractHttpTranslationProvider.requireEncrypted("ftp://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbstractHttpTranslationProvider.requireEncrypted("  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AbstractHttpTranslationProvider.requireEncrypted("https://example.com").getScheme())
                .isEqualTo("https");
    }
}
