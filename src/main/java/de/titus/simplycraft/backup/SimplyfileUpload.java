package de.titus.simplycraft.backup;

import java.time.Instant;

/**
 * Upload state of a backup in Simplyfile, stored next to the archive as &lt;name&gt;.json.
 *
 * @param status UPLOADING, DONE or FAILED
 */
public record SimplyfileUpload(String status, String fileId, String url, String error, Instant at) {
}
