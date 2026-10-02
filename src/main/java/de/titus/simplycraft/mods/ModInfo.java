package de.titus.simplycraft.mods;

import java.time.Instant;

/** @param modrinth set when the mod was installed from Modrinth */
public record ModInfo(String fileName, String name, boolean enabled, long size, Instant modifiedAt, ModSource modrinth) {
}
