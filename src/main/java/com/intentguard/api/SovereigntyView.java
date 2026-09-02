package com.intentguard.api;

/**
 * A live, verifiable statement of IntentGuard's data-sovereignty posture: which LLM backend is
 * active, where it runs, and the guarantee that no command data leaves the operator's network.
 *
 * <p>This surfaces the "Sovereign AI" claim as an inspectable runtime fact rather than a slide
 * bullet: the dashboard (and jury) can hit {@code GET /api/sovereignty} and see the actual
 * configured provider, model, and endpoint host driving every decision.
 *
 * @param llmProvider        the active LLM provider identity ({@code ollama} or {@code gemini})
 * @param model              the model driving semantic scoring / explanations / translation
 * @param endpointHost       the host serving inference (e.g. an on-premise BT server), or {@code local}
 * @param onPremise          true when inference runs on a self-hosted / on-premise endpoint
 * @param dataLeavesNetwork  false when no command/telemetry is sent to third-party cloud LLMs
 * @param inferenceLocation  a human-readable description of where inference happens
 * @param languagesSupported the number of Indian languages supported for explainability
 * @param statement          a one-line sovereignty guarantee for display
 */
public record SovereigntyView(
        String llmProvider,
        String model,
        String endpointHost,
        boolean onPremise,
        boolean dataLeavesNetwork,
        String inferenceLocation,
        int languagesSupported,
        String statement) {
}
