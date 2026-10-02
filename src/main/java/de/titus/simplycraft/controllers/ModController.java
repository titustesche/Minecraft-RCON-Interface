package de.titus.simplycraft.controllers;

import de.titus.simplycraft.modrinth.ModrinthModels;
import de.titus.simplycraft.modrinth.ModrinthService;
import de.titus.simplycraft.mods.ModInfo;
import de.titus.simplycraft.mods.ModService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ModController {

    private final ModService mods;
    private final ModrinthService modrinth;

    public ModController(ModService mods, ModrinthService modrinth) {
        this.mods = mods;
        this.modrinth = modrinth;
    }

    public record EnabledRequest(boolean enabled) {
    }

    public record ModrinthInstallRequest(String projectId, String versionId) {
    }

    @GetMapping("servers/{id}/mods")
    public List<ModInfo> list(@PathVariable String id) {
        return mods.list(id);
    }

    @PostMapping(path = "servers/{id}/mods", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<ModInfo> upload(@PathVariable String id, @RequestParam("files") List<MultipartFile> files) {
        return mods.upload(id, files);
    }

    @PatchMapping("servers/{id}/mods/{fileName}")
    public ModInfo setEnabled(@PathVariable String id, @PathVariable String fileName, @RequestBody EnabledRequest request) {
        return mods.setEnabled(id, fileName, request.enabled());
    }

    @DeleteMapping("servers/{id}/mods/{fileName}")
    public ResponseEntity<Void> delete(@PathVariable String id, @PathVariable String fileName) {
        mods.delete(id, fileName);
        return ResponseEntity.noContent().build();
    }

    // --- Modrinth -----------------------------------------------------------------------

    /** Searches Modrinth, filtered by the server's loader and Minecraft version */
    @GetMapping("servers/{id}/modrinth/search")
    public ModrinthModels.SearchResult search(@PathVariable String id,
                                              @RequestParam(defaultValue = "") String query,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "20") int limit) {
        return modrinth.search(id, query, offset, limit);
    }

    @GetMapping("servers/{id}/modrinth/projects/{projectId}/versions")
    public List<ModrinthModels.Version> versions(@PathVariable String id, @PathVariable String projectId) {
        return modrinth.versions(id, projectId);
    }

    /** Installs the newest compatible version (or the given one) plus required dependencies */
    @PostMapping("servers/{id}/modrinth/install")
    public ModrinthService.InstallResult install(@PathVariable String id, @RequestBody ModrinthInstallRequest request) {
        return modrinth.install(id, request.projectId(), request.versionId());
    }
}
