package de.titus.simplycraft.controllers;

import de.titus.simplycraft.jar.JarService;
import de.titus.simplycraft.jar.MinecraftVersion;
import de.titus.simplycraft.jar.ServerType;
import de.titus.simplycraft.jar.VersionCatalog;
import de.titus.simplycraft.process.ServerProcessManager;
import de.titus.simplycraft.process.ServerStatus;
import de.titus.simplycraft.server.CreateServerRequest;
import de.titus.simplycraft.server.ServerConfig;
import de.titus.simplycraft.server.ServerService;
import de.titus.simplycraft.server.UpdateServerRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ServerController {

    private final ServerService servers;
    private final ServerProcessManager processes;
    private final JarService jars;
    private final VersionCatalog versions;

    public ServerController(ServerService servers, ServerProcessManager processes, JarService jars, VersionCatalog versions) {
        this.servers = servers;
        this.processes = processes;
        this.jars = jars;
        this.versions = versions;
    }

    public record ServerView(ServerConfig config, ServerStatus status, boolean jarInstalled) {
    }

    public record InstallVersionRequest(@NotNull ServerType type, @NotBlank String version) {
    }

    public record InstallUrlRequest(@NotBlank String url) {
    }

    public record CommandRequest(@NotBlank String command) {
    }

    // --- servers ------------------------------------------------------------------------

    @GetMapping("servers")
    public List<ServerView> list() {
        return servers.list().stream().map(this::view).toList();
    }

    @PostMapping("servers")
    public ResponseEntity<ServerView> create(@Valid @RequestBody CreateServerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(servers.create(request)));
    }

    @GetMapping("servers/{id}")
    public ServerView get(@PathVariable String id) {
        return view(servers.get(id));
    }

    @PatchMapping("servers/{id}")
    public ServerView update(@PathVariable String id, @Valid @RequestBody UpdateServerRequest request) {
        return view(servers.update(id, request));
    }

    @DeleteMapping("servers/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        processes.requireStopped(id, "ihn löschst");
        servers.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- server jar ---------------------------------------------------------------------

    @GetMapping("versions")
    public List<MinecraftVersion> versions(@RequestParam(defaultValue = "VANILLA") ServerType type,
                                           @RequestParam(defaultValue = "false") boolean snapshots) {
        return versions.versions(type, snapshots);
    }

    @PostMapping("servers/{id}/jar/version")
    public ServerView installVersion(@PathVariable String id, @Valid @RequestBody InstallVersionRequest request) {
        processes.requireStopped(id, "die Serverdatei austauschst");
        return view(jars.installVersion(id, request.type(), request.version().trim()));
    }

    @PostMapping("servers/{id}/jar/url")
    public ServerView installUrl(@PathVariable String id, @Valid @RequestBody InstallUrlRequest request) {
        processes.requireStopped(id, "die Serverdatei austauschst");
        return view(jars.installFromUrl(id, request.url()));
    }

    @PostMapping(path = "servers/{id}/jar/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ServerView installUpload(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        processes.requireStopped(id, "die Serverdatei austauschst");
        return view(jars.installUpload(id, file));
    }

    // --- process ------------------------------------------------------------------------

    @PostMapping("servers/{id}/start")
    public ServerStatus start(@PathVariable String id) {
        return processes.start(id);
    }

    @PostMapping("servers/{id}/stop")
    public ServerStatus stop(@PathVariable String id) {
        return processes.stop(id);
    }

    @PostMapping("servers/{id}/restart")
    public ServerStatus restart(@PathVariable String id) {
        return processes.restart(id);
    }

    @PostMapping("servers/{id}/kill")
    public ServerStatus kill(@PathVariable String id) {
        return processes.kill(id);
    }

    @PostMapping("servers/{id}/command")
    public ResponseEntity<Void> command(@PathVariable String id, @Valid @RequestBody CommandRequest request) {
        processes.command(id, request.command());
        return ResponseEntity.accepted().build();
    }

    /**
     * Server-sent events: "status" (state, players, memory; every 5 s and on change),
     * "backlog" (last lines once after connecting) and "line" (every new console line).
     */
    @GetMapping(path = "servers/{id}/console", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter console(@PathVariable String id) {
        servers.get(id);
        return processes.runtime(id).subscribe();
    }

    private ServerView view(ServerConfig config) {
        return new ServerView(config, processes.status(config.getId()), java.nio.file.Files.isRegularFile(servers.jar(config.getId())));
    }
}
