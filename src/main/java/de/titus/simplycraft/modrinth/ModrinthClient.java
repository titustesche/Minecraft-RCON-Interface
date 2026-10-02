package de.titus.simplycraft.modrinth;

import java.util.List;

public interface ModrinthClient {

    /** Server-side mods matching the query; loader and gameVersion are optional filters */
    ModrinthModels.SearchResult search(String query, String loader, String gameVersion, int offset, int limit);

    ModrinthModels.Project project(String idOrSlug);

    /** Versions of a project, newest first */
    List<ModrinthModels.Version> versions(String projectId, String loader, String gameVersion);

    ModrinthModels.Version version(String versionId);
}
