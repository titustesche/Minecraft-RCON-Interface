package de.titus.simplycraft.jar;

import java.util.List;

/** Response shapes of piston-meta.mojang.com and meta.fabricmc.net (only the fields we use) */
final class ExternalModels {

    private ExternalModels() {
    }

    record MojangManifest(Latest latest, List<Entry> versions) {
        record Latest(String release, String snapshot) {
        }

        record Entry(String id, String type, String url, String sha1, String releaseTime) {
        }
    }

    record MojangVersion(Downloads downloads) {
        record Downloads(Download server) {
        }

        record Download(String url, String sha1, long size) {
        }
    }

    record FabricGame(String version, boolean stable) {
    }

    record FabricComponent(String version, boolean stable) {
    }
}
