package de.titus.simplycraft.jar;

import de.titus.simplycraft.server.CreateServerRequest;
import de.titus.simplycraft.server.ServerConfig;
import de.titus.simplycraft.server.ServerService;
import de.titus.simplycraft.support.IntegrationTest;
import de.titus.simplycraft.support.TestJars;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JarIntegrationTest extends IntegrationTest {

    private static final byte[] VANILLA_JAR = TestJars.jar("vanilla-1.21.1");
    private static final byte[] FABRIC_JAR = TestJars.jar("fabric-launcher");

    @Autowired
    ServerService servers;

    @BeforeAll
    static void fakeMojangAndFabric() {
        String base = FAKE.url();
        FAKE.json("/mojang/manifest.json", """
                {"latest": {"release": "1.21.1", "snapshot": "24w40a"},
                 "versions": [
                   {"id": "24w40a", "type": "snapshot", "url": "%1$s/mojang/24w40a.json"},
                   {"id": "1.21.1", "type": "release", "url": "%1$s/mojang/1.21.1.json"},
                   {"id": "1.0", "type": "release", "url": "%1$s/mojang/1.0.json"},
                   {"id": "b1.7.3", "type": "old_beta", "url": "%1$s/mojang/b1.7.3.json"}
                 ]}""".formatted(base));
        FAKE.json("/mojang/1.21.1.json", """
                {"id": "1.21.1", "downloads": {"server": {"url": "%s/mojang/server-1.21.1.jar", "sha1": "%s", "size": %d}}}"""
                .formatted(base, TestJars.sha1(VANILLA_JAR), VANILLA_JAR.length));
        FAKE.json("/mojang/1.0.json", """
                {"id": "1.0", "downloads": {"server": {"url": "%s/mojang/server-1.21.1.jar", "sha1": "0000000000000000000000000000000000000000", "size": 1}}}"""
                .formatted(base));
        FAKE.bytes("/mojang/server-1.21.1.jar", VANILLA_JAR);

        FAKE.json("/fabric/versions/game", """
                [{"version": "1.21.1", "stable": true}, {"version": "24w40a", "stable": false}]""");
        FAKE.json("/fabric/versions/loader", """
                [{"version": "0.17.0-beta", "stable": false}, {"version": "0.16.5", "stable": true}]""");
        FAKE.json("/fabric/versions/installer", """
                [{"version": "1.0.1", "stable": true}]""");
        FAKE.bytes("/fabric/versions/loader/1.21.1/0.16.5/1.0.1/server/jar", FABRIC_JAR);

        FAKE.bytes("/downloads/paper.jar", TestJars.jar("paper"));
        FAKE.bytes("/downloads/not-a-jar.html", "<html>404</html>".getBytes());
    }

    private String server(String name) {
        return servers.create(new CreateServerRequest(name, null, null, null)).getId();
    }

    @Test
    void listsReleasesAndOptionallySnapshots() throws Exception {
        mvc.perform(get("/api/versions").param("type", "VANILLA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("1.21.1"));
        mvc.perform(get("/api/versions").param("type", "VANILLA").param("snapshots", "true"))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/versions").param("type", "FABRIC"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void installsVanillaVersionWithChecksum() throws Exception {
        String id = server("Jar Vanilla");
        mvc.perform(post("/api/servers/{id}/jar/version", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VANILLA\",\"version\":\"1.21.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jarInstalled").value(true))
                .andExpect(jsonPath("$.config.jar.source").value("VANILLA"))
                .andExpect(jsonPath("$.config.gameVersion").value("1.21.1"))
                .andExpect(jsonPath("$.config.loader").value("vanilla"));
        assertArrayEquals(VANILLA_JAR, Files.readAllBytes(servers.jar(id)));
    }

    @Test
    void checksumMismatchKeepsOldJar() throws Exception {
        String id = server("Jar Checksum");
        Files.write(servers.jar(id), TestJars.jar("old"));
        mvc.perform(post("/api/servers/{id}/jar/version", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VANILLA\",\"version\":\"1.0\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Prüfsumme")));
        assertArrayEquals(TestJars.jar("old"), Files.readAllBytes(servers.jar(id)));
        try (var files = Files.list(servers.dir(id))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().contains(".part")));
        }
    }

    @Test
    void installsFabricLauncher() throws Exception {
        String id = server("Jar Fabric");
        mvc.perform(post("/api/servers/{id}/jar/version", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FABRIC\",\"version\":\"1.21.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.loader").value("fabric"));
        assertArrayEquals(FABRIC_JAR, Files.readAllBytes(servers.jar(id)));
        assertEquals("serverJar=vanilla-server.jar", Files.readString(servers.dir(id).resolve("fabric-server-launcher.properties")).trim());
    }

    @Test
    void unknownVersionIsRejected() throws Exception {
        String id = server("Jar Unknown");
        mvc.perform(post("/api/servers/{id}/jar/version", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VANILLA\",\"version\":\"9.9.9\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void installsFromUrlAndRejectsNonJars() throws Exception {
        String id = server("Jar Url");
        mvc.perform(post("/api/servers/{id}/jar/url", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + FAKE.url() + "/downloads/paper.jar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.jar.source").value("URL"));

        mvc.perform(post("/api/servers/{id}/jar/url", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + FAKE.url() + "/downloads/not-a-jar.html\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/servers/{id}/jar/url", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"file:///etc/passwd\"}"))
                .andExpect(status().isBadRequest());
        assertArrayEquals(TestJars.jar("paper"), Files.readAllBytes(servers.jar(id)));
    }

    @Test
    void installsUpload() throws Exception {
        String id = server("Jar Upload");
        mvc.perform(multipart("/api/servers/{id}/jar/upload", id)
                        .file(new MockMultipartFile("file", "forge-installer.jar", "application/java-archive", TestJars.jar("upload"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.jar.source").value("UPLOAD"))
                .andExpect(jsonPath("$.config.jar.origin").value("forge-installer.jar"));
        ServerConfig config = servers.get(id);
        assertEquals("UPLOAD", config.getJar().source());
    }
}
