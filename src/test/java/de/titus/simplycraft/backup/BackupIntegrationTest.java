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
}
