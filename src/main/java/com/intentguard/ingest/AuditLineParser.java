package com.intentguard.ingest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.intentguard.domain.RawAuditEvent;

/**
 * Userspace parser for auditd records (Req 2.1, 2.5).
 *
 * <p>This is intentionally a <em>pure</em>, side-effect-free parser so it is fully testable
 * without a running auditd: callers feed it individual log lines (e.g. produced by tailing the
 * audit log, {@code ausearch}, or an {@code auditd} plugin) and it returns a normalized
 * {@link RawAuditEvent}, or {@link Optional#empty()} for lines that are not relevant execve /
 * file-write records. No kernel module is required (Req 2.5) — this consumes text lines only.
 *
 * <p>It understands the standard auditd {@code key=value} token format, including:
 * <ul>
 *   <li>{@code type=EXECVE} / {@code type=SYSCALL syscall=execve} records &rarr;
 *       {@link RawAuditEvent.AuditType#EXECVE};</li>
 *   <li>{@code type=PATH} records with a {@code name=} &rarr;
 *       {@link RawAuditEvent.AuditType#FILE_WRITE};</li>
 *   <li>the {@code msg=audit(<seconds>.<millis>:<serial>)} stamp &rarr; UTC epoch millis;</li>
 *   <li>user identity from {@code auid} (login uid) with a fallback to {@code uid};</li>
 *   <li>the command line from {@code a0..aN} execve arguments, or {@code cmd} / {@code proctitle}
 *       / {@code exe} / {@code comm} as fallbacks;</li>
 *   <li>the working directory from {@code cwd}.</li>
 * </ul>
 *
 * <p>Quoted values ({@code exe="/usr/bin/bash"}) are unquoted. The parser is defensive: a line
 * that cannot be recognized as an execve or file-write record yields {@link Optional#empty()}
 * rather than throwing, so a noisy audit stream never stalls ingestion.
 */
@Component
public class AuditLineParser {

    private static final String UNSET_UID = "4294967295"; // AUDIT_UID_UNSET

    /**
     * Parse a single auditd log line into a {@link RawAuditEvent}.
     *
     * @param line a raw auditd record line (may be {@code null} or blank)
     * @return the parsed event, or empty if the line is not a relevant execve/file-write record
     */
    public Optional<RawAuditEvent> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }

        Map<String, String> fields = tokenize(line);
        if (fields.isEmpty()) {
            return Optional.empty();
        }

        RawAuditEvent.AuditType type = classify(fields);
        if (type == null) {
            return Optional.empty();
        }

        long timestamp = parseTimestamp(fields.get("__audit_msg"));
        String userId = resolveUser(fields);

        if (type == RawAuditEvent.AuditType.FILE_WRITE) {
            String path = fields.get("name");
            if (path == null || path.isBlank()) {
                // A PATH record without a name is not actionable as a file-write signal.
                return Optional.empty();
            }
            return Optional.of(
                    new RawAuditEvent(
                            RawAuditEvent.AuditType.FILE_WRITE,
                            userId,
                            null,
                            path,
                            fields.get("cwd"),
                            timestamp));
        }

        String commandText = reconstructCommand(fields);
        return Optional.of(
                new RawAuditEvent(
                        RawAuditEvent.AuditType.EXECVE,
                        userId,
                        commandText,
                        null,
                        fields.get("cwd"),
                        timestamp));
    }

    private static RawAuditEvent.AuditType classify(Map<String, String> fields) {
        String type = fields.get("type");
        if (type != null) {
            if ("EXECVE".equalsIgnoreCase(type)) {
                return RawAuditEvent.AuditType.EXECVE;
            }
            if ("PATH".equalsIgnoreCase(type)) {
                return RawAuditEvent.AuditType.FILE_WRITE;
            }
            if ("SYSCALL".equalsIgnoreCase(type)) {
                return classifySyscall(fields);
            }
            return null;
        }
        // No explicit type token: infer from a syscall field if present.
        return fields.containsKey("syscall") ? classifySyscall(fields) : null;
    }

    private static RawAuditEvent.AuditType classifySyscall(Map<String, String> fields) {
        String syscall = fields.get("syscall");
        if (syscall == null) {
            return null;
        }
        if ("execve".equalsIgnoreCase(syscall) || "execveat".equalsIgnoreCase(syscall)
                || "59".equals(syscall) || "322".equals(syscall)) {
            return RawAuditEvent.AuditType.EXECVE;
        }
        if (isFileWriteSyscall(syscall)) {
            return RawAuditEvent.AuditType.FILE_WRITE;
        }
        return null;
    }

    private static boolean isFileWriteSyscall(String syscall) {
        switch (syscall.toLowerCase()) {
            case "write":
            case "writev":
            case "pwrite":
            case "pwrite64":
            case "open":
            case "openat":
            case "creat":
            case "truncate":
            case "ftruncate":
            case "rename":
            case "renameat":
            case "renameat2":
            case "unlink":
            case "unlinkat":
                return true;
            default:
                return false;
        }
    }

    private static String resolveUser(Map<String, String> fields) {
        String auid = fields.get("auid");
        if (auid != null && !auid.isBlank() && !UNSET_UID.equals(auid) && !"-1".equals(auid)
                && !"unset".equalsIgnoreCase(auid)) {
            return auid;
        }
        String uid = fields.get("uid");
        if (uid != null && !uid.isBlank()) {
            return uid;
        }
        // Fall back to auid even when unset rather than producing a null identity, which
        // RawAuditEvent forbids; an unknown identity is represented as the literal value.
        return auid != null && !auid.isBlank() ? auid : "unknown";
    }

    /**
     * Reconstruct the command line, preferring the exact execve argument vector when present.
     */
    private static String reconstructCommand(Map<String, String> fields) {
        StringBuilder args = new StringBuilder();
        for (int i = 0; fields.containsKey("a" + i); i++) {
            if (args.length() > 0) {
                args.append(' ');
            }
            args.append(fields.get("a" + i));
        }
        if (args.length() > 0) {
            return args.toString();
        }
        for (String key : new String[] {"cmd", "proctitle", "exe", "comm"}) {
            String value = fields.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Parse {@code audit(<seconds>.<millis>:<serial>)} into UTC epoch millis. Returns {@code 0}
     * when the stamp is absent or malformed.
     */
    private static long parseTimestamp(String auditMsg) {
        if (auditMsg == null) {
            return 0L;
        }
        // auditMsg holds the content between "audit(" and ")", e.g. "1710000000.123:456".
        String timePart = auditMsg;
        int colon = timePart.indexOf(':');
        if (colon >= 0) {
            timePart = timePart.substring(0, colon);
        }
        int dot = timePart.indexOf('.');
        try {
            if (dot < 0) {
                return Long.parseLong(timePart.trim()) * 1000L;
            }
            long seconds = Long.parseLong(timePart.substring(0, dot).trim());
            String millisStr = timePart.substring(dot + 1).trim();
            long millis = millisStr.isEmpty() ? 0L : Long.parseLong(millisStr);
            return seconds * 1000L + millis;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Split an auditd line into {@code key=value} pairs, handling quoted values and the special
     * {@code msg=audit(...)} stamp (stored under the synthetic key {@code __audit_msg}).
     */
    private static Map<String, String> tokenize(String line) {
        Map<String, String> fields = new LinkedHashMap<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            // Skip leading whitespace.
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int keyStart = i;
            while (i < n && line.charAt(i) != '=' && !Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n || line.charAt(i) != '=') {
                // A bare token with no '='; skip to next whitespace.
                while (i < n && !Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
                continue;
            }
            String key = line.substring(keyStart, i);
            i++; // consume '='

            String value;
            if (i < n && "msg".equals(key) && line.startsWith("audit(", i)) {
                int close = line.indexOf(')', i);
                if (close < 0) {
                    close = n;
                }
                value = line.substring(i + "audit(".length(), close);
                fields.put("__audit_msg", value);
                i = close < n ? close + 1 : n;
                continue;
            }

            if (i < n && line.charAt(i) == '"') {
                int close = line.indexOf('"', i + 1);
                if (close < 0) {
                    value = line.substring(i + 1);
                    i = n;
                } else {
                    value = line.substring(i + 1, close);
                    i = close + 1;
                }
            } else {
                int valStart = i;
                while (i < n && !Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
                value = line.substring(valStart, i);
            }
            fields.put(key, value);
        }
        return fields;
    }
}
