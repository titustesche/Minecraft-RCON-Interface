package de.titus.simplycraft.controllers;

import de.titus.simplycraft.backup.BackupFormat;
import de.titus.simplycraft.backup.BackupInfo;
import de.titus.simplycraft.backup.BackupService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/servers/{id}/backups")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    public record CreateBackupRequest(BackupFormat format, boolean uploadToSimplyfile) {
    }

    @GetMapping
    public List<BackupInfo> list(@PathVariable String id) {
        return backups.list(id);
    }

    @PostMapping
    public ResponseEntity<BackupInfo> create(@PathVariable String id, @RequestBody(required = false) CreateBackupRequest request) {
        BackupFormat format = request != null && request.format() != null ? request.format() : BackupFormat.ZIP;
        boolean upload = request != null && request.uploadToSimplyfile();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(backups.create(id, format, upload));
    }

    @GetMapping("{name}")
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable String name) {
        Path file = backups.file(id, name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("{name}")
    public ResponseEntity<Void> delete(@PathVariable String id, @PathVariable String name) {
        backups.delete(id, name);
        return ResponseEntity.noContent().build();
    }

    /** (Re-)uploads an existing backup to Simplyfile */
    @PostMapping("{name}/simplyfile")
    public ResponseEntity<BackupInfo> upload(@PathVariable String id, @PathVariable String name) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(backups.upload(id, name));
    }
}
