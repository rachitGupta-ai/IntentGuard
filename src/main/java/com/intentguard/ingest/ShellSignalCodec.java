package com.intentguard.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intentguard.domain.RawShellSignal;
import com.intentguard.domain.Verdict;

/**
 * JSON wire codec for the Shell_Hook blocking-gate protocol.
 *
 * <p>The hook sends a single JSON object (see {@link ShellHookRequest}) and reads back a single
 * JSON verdict line of the form
 * {@code {"action":"ALLOW|ASK|BLOCK","reasonCode":"...","explanation":"..."}}.
 * A newline terminates each message so the hook can read a line and stop.
 */
@Component
public class ShellSignalCodec {

    private final ObjectMapper mapper;

    public ShellSignalCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Decode a Shell_Hook request payload into a domain {@link RawShellSignal}.
     *
     * @param json the raw JSON request bytes as text
     * @return the decoded signal
     * @throws JsonProcessingException if the payload is not valid request JSON
     */
    public RawShellSignal decodeRequest(String json) throws JsonProcessingException {
        ShellHookRequest request = mapper.readValue(json, ShellHookRequest.class);
        return request.toDomain();
    }

    /**
     * Encode a {@link Verdict} as a newline-terminated JSON line for the hook to read.
     *
     * @param verdict the verdict to send
     * @return the encoded JSON line, including a trailing newline
     */
    public String encodeVerdict(Verdict verdict) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", verdict.action().name());
        out.put("reasonCode", verdict.reasonCode());
        out.put("explanation", verdict.explanation());
        try {
            return mapper.writeValueAsString(out) + "\n";
        } catch (JsonProcessingException e) {
            // A verdict is a tiny, well-formed structure; serialization cannot realistically fail.
            throw new IllegalStateException("Failed to encode verdict", e);
        }
    }
}
