package de.titus.simplycraft.backup;

import java.time.Instant;

public record BackupInfo(String name, BackupFormat format, long size, Instant createdAt, boolean inProgress, SimplyfileUpload simplyfile) {
}
