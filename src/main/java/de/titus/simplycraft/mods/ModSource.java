package de.titus.simplycraft.mods;

/** Remembers which Modrinth project/version a mod file came from */
public record ModSource(String projectId, String slug, String versionId, String title, String versionNumber, String iconUrl) {
}
