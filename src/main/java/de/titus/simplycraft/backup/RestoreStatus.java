package de.titus.simplycraft.backup;

import java.time.Instant;

/**
 * Last restore of a server.
 *
 * @param status       RUNNING, DONE or FAILED
 * @param safetyBackup name of the backup taken right before, if any
 */
public record RestoreStatus(String backup, String status, String safetyBackup, String error, Instant at) {
}
