package com.intentguard.blastradius;

/**
 * Classification of a {@link ProtectedTarget} (Req 3.1).
 *
 * <ul>
 *   <li>{@code PATH} - a sensitive filesystem path (for example {@code ~/.ssh/**}, {@code /etc},
 *       or a cloud-credential file).</li>
 *   <li>{@code HOST} - a sensitive host or network destination.</li>
 *   <li>{@code RESOURCE} - a sensitive logical resource, such as a production database or a tagged
 *       cloud resource.</li>
 * </ul>
 */
public enum TargetKind {
    PATH,
    HOST,
    RESOURCE
}
