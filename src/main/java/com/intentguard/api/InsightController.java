package com.intentguard.api;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intentguard.llm.LlmProperties;
import com.intentguard.llm.OllamaProperties;
import com.intentguard.persistence.AuditHistoryRepository;
import com.intentguard.persistence.IntentSessionRepository;

/**
 * Read-only "insight" surface of the Control_Tower API: explainability and sovereignty views that
 * make IntentGuard's decisions and trust posture inspectable at runtime.
 *
 * <p>These endpoints are purely additive projections over already-persisted state
 * ({@link AuditHistoryRepository}) and already-bound configuration ({@link OllamaProperties},
 * {@link LlmProperties}); they do not alter any enforcement path.
 *
 * <ul>
 *   <li>{@code GET /api/explain/{eventId}} — the full per-component "why" breakdown for a scored
 *       Command_Event, ranked by applied contribution, with the top contributor and profile state.</li>
 *   <li>{@code GET /api/sovereignty} — a live statement of which LLM backend is active, where it
 *       runs, and the guarantee that no command data leaves the operator's network.</li>
 * </ul>
 *
 * <p><strong>SECURITY — UNAUTHENTICATED PROTOTYPE ENDPOINTS.</strong> Like the rest of the
 * Control_Tower API, these have no authentication layer and must be bound to a loopback/OS-restricted
 * interface before any non-prototype use.
 */
@RestController
@RequestMapping("/api")
public class InsightController {

    /** Maximum audit records returned by the bootstrap hydration to bound payload size. */
    private static final int BOOTSTRAP_MAX_RECORDS = 500;

    /** Maximum look-back window (days) for bootstrap hydration. Default is 3 (see endpoint). */
    private static final int MAX_BOOTSTRAP_DAYS = 30;

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    private final AuditHistoryRepository auditHistoryRepository;
    private final IntentSessionRepository intentSessionRepository;
    private final OllamaProperties ollamaProperties;
    private final LlmProperties llmProperties;
    private final String activeProvider;

    public InsightController(
            AuditHistoryRepository auditHistoryRepository,
            IntentSessionRepository intentSessionRepository,
            OllamaProperties ollamaProperties,
            LlmProperties llmProperties,
            @Value("${intentguard.llm.provider:gemini}") String activeProvider) {
        this.auditHistoryRepository = auditHistoryRepository;
        this.intentSessionRepository = intentSessionRepository;
        this.ollamaProperties = ollamaProperties;
        this.llmProperties = llmProperties;
        this.activeProvider = activeProvider;
    }

    /**
     * Hydrates the Control_Tower dashboard from persisted MongoDB state for the last {@code days}
     * (default 3, capped at 30). Projects recent Intent_Sessions and Audit_History into the same
     * event shapes the live SSE channel emits, so a fresh page load reflects historical activity
     * immediately instead of starting empty.
     */
    @GetMapping("/bootstrap")
    public BootstrapView bootstrap(@RequestParam(name = "days", defaultValue = "3") int days) {
        int windowDays = Math.max(1, Math.min(days, MAX_BOOTSTRAP_DAYS));
        long sinceMs = System.currentTimeMillis() - windowDays * MILLIS_PER_DAY;
        return BootstrapView.from(
                intentSessionRepository.findRecentOrOpen(sinceMs),
                auditHistoryRepository.findSince(sinceMs, BOOTSTRAP_MAX_RECORDS),
                sinceMs);
    }

    /**
     * Returns the full explainability breakdown for a scored Command_Event: every component's
     * score, weight, and applied contribution (ranked highest-first), the top contributor, the
     * verdict, reason code, and profile state. HTTP 404 when no such event was recorded.
     */
    @GetMapping("/explain/{eventId}")
    public ResponseEntity<ExplainView> explain(@PathVariable String eventId) {
        return auditHistoryRepository.findByEventId(eventId)
                .map(doc -> ResponseEntity.ok(ExplainView.from(doc)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns a live statement of IntentGuard's data-sovereignty posture derived from the active
     * runtime configuration, so the "no data leaves the network" guarantee is inspectable rather
     * than merely asserted.
     */
    @GetMapping("/sovereignty")
    public SovereigntyView sovereignty() {
        boolean ollama = "ollama".equalsIgnoreCase(activeProvider);
        if (ollama) {
            String host = hostOf(ollamaProperties.getBaseUrl());
            boolean localhost = host.contains("localhost") || host.contains("127.0.0.1");
            return new SovereigntyView(
                    "ollama",
                    ollamaProperties.getModel(),
                    host,
                    true,
                    false,
                    localhost ? "on this machine (local Ollama)" : "on-premise server: " + host,
                    11,
                    "All inference runs on a self-hosted model. No command text, path, or "
                            + "telemetry is sent to any third-party cloud LLM.");
        }
        // Gemini path (cloud). Reported honestly.
        return new SovereigntyView(
                "gemini",
                llmProperties.getModel(),
                "generativelanguage.googleapis.com",
                false,
                true,
                "Google Gemini cloud API",
                11,
                "Running on the Google Gemini cloud backend. Switch intentguard.llm.provider=ollama "
                        + "for a fully on-premise, sovereign deployment.");
    }

    /** Extracts the host from a base URL, falling back to the raw value if it will not parse. */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "local";
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? baseUrl : host;
        } catch (IllegalArgumentException malformed) {
            return baseUrl;
        }
    }
}
