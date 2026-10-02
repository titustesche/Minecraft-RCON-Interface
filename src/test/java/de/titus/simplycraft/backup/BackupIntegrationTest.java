package de.titus.simplycraft.backup;

import de.titus.simplycraft.process.ServerProcessManager;
import de.titus.simplycraft.process.ServerState;
import de.titus.simplycraft.server.CreateServerRequest;
import de.titus.simplycraft.server.ServerService;
import de.titus.simplycraft.server.UpdateServerRequest;
import de.titus.simplycraft.support.IntegrationTest;
import de.titus.simplycraft.support.Wait;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.mock.web.MockMultipartFile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BackupIntegrationTest extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ServerService servers;

    @Autowired
    ServerProcessManager processes;

    @Autowired
    BackupService backups;

    @BeforeAll
    static void fakeSimplyfile() {
        FAKE.json("/simplyfile/file/upload", "{\"id\":\"7b0d3f4e-1111-2222-3333-444455556666\",\"filename\":\"x\"}");
    }

    private String serverWithWorld(String name) throws Exception {
        String id = servers.create(new CreateServerRequest(name, null, null, null)).getId();
        Files.createDirectories(servers.dir(id).resolve("world/region"));
        Files.writeString(servers.dir(id).resolve("world/level.dat"), "level");
        Files.writeString(servers.dir(id).resolve("world/region/r.0.0.mca"), "region");
        Files.writeString(servers.dir(id).resolve("world/session.lock"), "lock");
        return id;
    }

    private BackupInfo awaitDone(String id, String name) {
        Wait.until("backup " + name, TIMEOUT, () -> backups.list(id).stream().anyMatch(b -> b.name().equals(name) && !b.inProgress()
                && (b.simplyfile() == null || !"UPLOADING".equals(b.simplyfile().status()))));
        return backups.list(id).stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void zipBackupOfRunningServerIsSavedAndUploadedToSimplyfile() throws Exception {
        String id = serverWithWorld("Backup Running");
        installFakeServer(servers.dir(id));
        servers.update(id, new UpdateServerRequest(null, null, null, null, null, "{java} FakeServer.java", true, null, null));
        processes.start(id);
        Wait.until("online", TIMEOUT, () -> processes.status(id).state() == ServerState.ONLINE);

        try {
            String body = mvc.perform(post("/api/servers/{id}/backups", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"format\":\"ZIP\",\"uploadToSimplyfile\":true}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.inProgress").value(true))
                    .andReturn().getResponse().getContentAsString();
            String name = body.replaceAll(".*\"name\":\"([^\"]+)\".*", "$1");

            BackupInfo info = awaitDone(id, name);
            assertEquals(BackupFormat.ZIP, info.format());
            assertEquals("DONE", info.simplyfile().status());
            assertEquals(FAKE.url() + "/simplyfile/file/7b0d3f4e-1111-2222-3333-444455556666", info.simplyfile().url());

            // The server was asked to flush and to pause saving, and saving is enabled again afterwards
            var commands = processes.runtime(id).backlog().stream().filter(l -> l.level().equals(">")).map(l -> l.text()).toList();
            assertTrue(commands.containsAll(List.of("save-off", "save-all flush", "save-on")), commands.toString());

            var upload = FAKE.requests.stream().filter(r -> r.uri().equals("/simplyfile/file/upload")).reduce((a, b) -> b).orElseThrow();
            assertTrue(upload.headers().get("Content-type").getFirst().startsWith("multipart/form-data"));
            assertTrue(upload.bodyText().contains("filename=\"" + name + "\""));

            byte[] zip = mvc.perform(get("/api/servers/{id}/backups/{name}", id, name))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(name)))
                    .andReturn().getResponse().getContentAsByteArray();
            List<String> entries = new ArrayList<>();
            try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) entries.add(e.getName());
            }
            assertTrue(entries.contains(id + "/world/level.dat"), entries.toString());
            assertTrue(entries.contains(id + "/world/region/r.0.0.mca"), entries.toString());
            assertTrue(entries.contains(id + "/FakeServer.java"), entries.toString());
            assertFalse(entries.contains(id + "/world/session.lock"), entries.toString());
        } finally {
            processes.stopAndWait(id);
        }
    }

    @Test
    void tarGzBackupCanBeUploadedLaterAndDeleted() throws Exception {
        String id = serverWithWorld("Backup Tar");
        BackupInfo started = backups.create(id, BackupFormat.TAR_GZ, false);
        assertTrue(started.name().endsWith(".tar.gz"));
        BackupInfo done = awaitDone(id, started.name());
        assertNull(done.simplyfile());
        assertTrue(done.size() > 0);

        List<String> entries = new ArrayList<>();
        try (var in = new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(backups.file(id, started.name()))))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) entries.add(e.getName());
        }
        assertTrue(entries.contains(id + "/world/level.dat"), entries.toString());
        assertTrue(entries.contains(id + "/simplycraft.json"), entries.toString());

        mvc.perform(post("/api/servers/{id}/backups/{name}/simplyfile", id, started.name())).andExpect(status().isAccepted());
        assertEquals("DONE", awaitDone(id, started.name()).simplyfile().status());

        mvc.perform(delete("/api/servers/{id}/backups/{name}", id, started.name())).andExpect(status().isNoContent());
        assertTrue(backups.list(id).isEmpty());
    }

    @Test
    void invalidNamesAreRejected() throws Exception {
        String id = serverWithWorld("Backup Names");
        mvc.perform(get("/api/servers/{id}/backups/{name}", id, "..%2F..%2Fsimplycraft.json")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/servers/{id}/backups/{name}", id, "whatever.zip")).andExpect(status().isBadRequest());
    }

    // --- restore & import ------------------------------------------------------------------

    private String awaitRestore(String id) {
        Wait.until("restore", TIMEOUT, () -> {
            var status = backups.restoreStatus(id);
            return status != null && !"RUNNING".equals(status.status());
        });
        return backups.restoreStatus(id).status();
    }

    @Test
    void restoreReplacesServerAndKeepsSafetyBackup() throws Exception {
        String id = serverWithWorld("Restore Zip");
        Path dir = servers.dir(id);
        String backup = backups.create(id, BackupFormat.ZIP, false).name();
        awaitDone(id, backup);

        // Changes after the backup
        Files.writeString(dir.resolve("world/level.dat"), "changed");
        Files.writeString(dir.resolve("world/new-chunk.mca"), "new");
        Files.delete(dir.resolve("world/region/r.0.0.mca"));
        servers.update(id, new UpdateServerRequest("Umbenannt", null, null, "6G", null, null, null, null, null));

        mvc.perform(post("/api/servers/{id}/backups/{name}/restore", id, backup).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safetyBackup\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        assertEquals("DONE", awaitRestore(id));

        assertEquals("level", Files.readString(dir.resolve("world/level.dat")));
        assertEquals("region", Files.readString(dir.resolve("world/region/r.0.0.mca")));
        assertFalse(Files.exists(dir.resolve("world/new-chunk.mca")));
        assertEquals("Restore Zip", servers.get(id).getName());
        assertEquals("4G", servers.get(id).getXmx());
        assertEquals(id, servers.get(id).getId());

        // The state before the restore was saved
        String safety = backups.restoreStatus(id).safetyBackup();
        assertNotNull(safety);
        List<String> entries = new ArrayList<>();
        try (var in = new ZipInputStream(Files.newInputStream(backups.file(id, safety)))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.add(e.getName());
                if (e.getName().equals(id + "/world/level.dat")) assertEquals("changed", new String(in.readAllBytes()));
            }
        }
        assertTrue(entries.contains(id + "/world/new-chunk.mca"), entries.toString());
        assertEquals(2, backups.list(id).size());

        mvc.perform(get("/api/servers/{id}/restore", id)).andExpect(jsonPath("$.status").value("DONE"));
        try (var staging = Files.list(dir.getParent())) {
            assertTrue(staging.noneMatch(p -> p.getFileName().toString().startsWith(".")), "staging directories left behind");
        }
    }

    @Test
    void restoresTarGzWithoutSafetyBackup() throws Exception {
        String id = serverWithWorld("Restore Tar");
        String backup = backups.create(id, BackupFormat.TAR_GZ, false).name();
        awaitDone(id, backup);
        Files.writeString(servers.dir(id).resolve("world/level.dat"), "changed");

        mvc.perform(post("/api/servers/{id}/backups/{name}/restore", id, backup).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safetyBackup\":false}"))
                .andExpect(status().isAccepted());
        assertEquals("DONE", awaitRestore(id));
        assertEquals("level", Files.readString(servers.dir(id).resolve("world/level.dat")));
        assertNull(backups.restoreStatus(id).safetyBackup());
        assertEquals(1, backups.list(id).size());
    }

    @Test
    void restoreIsRefusedWhileServerRuns() throws Exception {
        String id = serverWithWorld("Restore Running");
        String backup = backups.create(id, BackupFormat.ZIP, false).name();
        awaitDone(id, backup);
        installFakeServer(servers.dir(id));
        servers.update(id, new UpdateServerRequest(null, null, null, null, null, "{java} FakeServer.java", true, null, null));
        processes.start(id);
        try {
            mvc.perform(post("/api/servers/{id}/backups/{name}/restore", id, backup))
                    .andExpect(status().isConflict());
        } finally {
            processes.stopAndWait(id);
        }
    }

    @Test
    void importedPlainServerArchiveCanBeRestored() throws Exception {
        String id = serverWithWorld("Restore Import");
        byte[] zip = zip(Map.of("world/level.dat", "imported", "server.properties", "motd=Importiert\n"));

        String body = mvc.perform(multipart("/api/servers/{id}/backups/upload", id)
                        .file(new MockMultipartFile("file", "alter-server.zip", "application/zip", zip)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String name = body.replaceAll(".*\"name\":\"([^\"]+)\".*", "$1");
        assertTrue(name.startsWith(id + "-") && name.endsWith(".zip"), name);

        backups.restore(id, name, false);
        assertEquals("DONE", awaitRestore(id));
        Path dir = servers.dir(id);
        assertEquals("imported", Files.readString(dir.resolve("world/level.dat")));
        assertEquals("motd=Importiert", Files.readString(dir.resolve("server.properties")).trim());
        assertFalse(Files.exists(dir.resolve("world/region")));
        // No config in the archive: the server keeps its settings
        assertEquals("Restore Import", servers.get(id).getName());
        assertTrue(Files.isDirectory(dir.resolve("mods")));
    }

    @Test
    void importRejectsBrokenAndMaliciousArchives() throws Exception {
        String id = serverWithWorld("Restore Evil");
        mvc.perform(multipart("/api/servers/{id}/backups/upload", id)
                        .file(new MockMultipartFile("file", "evil.zip", "application/zip", zip(Map.of("../../evil.txt", "x")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ungültigen Pfad")));
        mvc.perform(multipart("/api/servers/{id}/backups/upload", id)
                        .file(new MockMultipartFile("file", "kaputt.zip", "application/zip", "not a zip".getBytes())))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/servers/{id}/backups/upload", id)
                        .file(new MockMultipartFile("file", "world.rar", "application/octet-stream", new byte[]{1})))
                .andExpect(status().isBadRequest());
        assertTrue(backups.list(id).isEmpty());
        assertFalse(Files.exists(ROOT.resolve("evil.txt")));
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (var e : files.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
