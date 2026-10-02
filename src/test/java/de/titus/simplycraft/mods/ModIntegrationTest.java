package de.titus.simplycraft.mods;

import de.titus.simplycraft.server.CreateServerRequest;
import de.titus.simplycraft.server.ServerService;
import de.titus.simplycraft.server.UpdateServerRequest;
import de.titus.simplycraft.support.FakeHttpServer;
import de.titus.simplycraft.support.IntegrationTest;
import de.titus.simplycraft.support.TestJars;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModIntegrationTest extends IntegrationTest {

    private static final byte[] SODIUM = TestJars.jar("sodium");
    private static final byte[] FABRIC_API = TestJars.jar("fabric-api");

    @Autowired
    ServerService servers;

    @BeforeAll
    static void fakeModrinth() {
        String base = FAKE.url();
        FAKE.json("/modrinth/search", """
                {"hits": [{"project_id": "AANobbMI", "slug": "sodium", "title": "Sodium", "description": "Fast", "author": "jellysquid3",
                           "downloads": 1234567, "icon_url": null, "categories": ["fabric", "optimization"], "versions": ["1.21.1"],
                           "server_side": "optional", "latest_version": "abc"}],
                 "offset": 0, "limit": 20, "total_hits": 1}""");
        FAKE.json("/modrinth/project/AANobbMI", """
                {"id": "AANobbMI", "slug": "sodium", "title": "Sodium", "description": "Fast", "icon_url": "https://cdn.example/sodium.png"}""");
        FAKE.json("/modrinth/project/P7dR8mSH", """
                {"id": "P7dR8mSH", "slug": "fabric-api", "title": "Fabric API", "description": "Core", "icon_url": null}""");
        FAKE.json("/modrinth/project/AANobbMI/version", """
                [{"id": "beta1", "project_id": "AANobbMI", "name": "Sodium beta", "version_number": "0.7.0-beta", "version_type": "beta",
                  "game_versions": ["1.21.1"], "loaders": ["fabric"], "files": [], "dependencies": []},
                 {"id": "sod061", "project_id": "AANobbMI", "name": "Sodium 0.6.1", "version_number": "0.6.1", "version_type": "release",
                  "game_versions": ["1.21.1"], "loaders": ["fabric"],
                  "files": [{"url": "%1$s/cdn/sodium-fabric-0.6.1.jar", "filename": "sodium-fabric-0.6.1.jar", "primary": true, "size": %2$d,
                             "hashes": {"sha1": "%3$s"}}],
                  "dependencies": [{"project_id": "P7dR8mSH", "version_id": null, "dependency_type": "required"},
                                   {"project_id": "Lithium0", "version_id": null, "dependency_type": "optional"}]}]"""
                .formatted(base, SODIUM.length, TestJars.sha1(SODIUM)));
        FAKE.json("/modrinth/project/P7dR8mSH/version", """
                [{"id": "fapi1", "project_id": "P7dR8mSH", "name": "Fabric API", "version_number": "0.105.0", "version_type": "release",
                  "game_versions": ["1.21.1"], "loaders": ["fabric"],
                  "files": [{"url": "%1$s/cdn/fabric-api-0.105.0.jar", "filename": "fabric-api-0.105.0.jar", "primary": true, "size": %2$d,
                             "hashes": {"sha1": "%3$s"}}],
                  "dependencies": []}]"""
                .formatted(base, FABRIC_API.length, TestJars.sha1(FABRIC_API)));
        FAKE.bytes("/cdn/sodium-fabric-0.6.1.jar", SODIUM);
        FAKE.bytes("/cdn/fabric-api-0.105.0.jar", FABRIC_API);
    }

    private String fabricServer(String name) {
        String id = servers.create(new CreateServerRequest(name, null, null, null)).getId();
        servers.update(id, new UpdateServerRequest(null, null, null, null, null, null, null, "fabric", "1.21.1"));
        return id;
    }

    @Test
    void uploadToggleAndDelete() throws Exception {
        String id = fabricServer("Mods Upload");
        mvc.perform(multipart("/api/servers/{id}/mods", id)
                        .file(new MockMultipartFile("files", "../../evil name.jar", "application/java-archive", TestJars.jar("a")))
                        .file(new MockMultipartFile("files", "worldedit.jar", "application/java-archive", TestJars.jar("b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        var mods = servers.dir(id).resolve("mods");
        assertTrue(Files.isRegularFile(mods.resolve("evil_name.jar")));
        assertFalse(Files.exists(servers.dir(id).resolve("evil name.jar")));

        mvc.perform(patch("/api/servers/{id}/mods/{file}", id, "worldedit.jar").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.fileName").value("worldedit.jar.disabled"));
        assertTrue(Files.exists(mods.resolve("worldedit.jar.disabled")));

        mvc.perform(get("/api/servers/{id}/mods", id))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].name").value("worldedit"));

        mvc.perform(delete("/api/servers/{id}/mods/{file}", id, "worldedit.jar.disabled")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/servers/{id}/mods/{file}", id, "..%2Fsimplycraft.json")).andExpect(status().is4xxClientError());
        assertTrue(Files.exists(servers.dir(id).resolve("simplycraft.json")));
    }

    @Test
    void rejectsNonJarUploads() throws Exception {
        String id = fabricServer("Mods Reject");
        mvc.perform(multipart("/api/servers/{id}/mods", id)
                        .file(new MockMultipartFile("files", "script.sh", "text/plain", "echo".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchFiltersByLoaderAndVersion() throws Exception {
        String id = fabricServer("Mods Search");
        mvc.perform(get("/api/servers/{id}/modrinth/search", id).param("query", "sod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_hits").value(1))
                .andExpect(jsonPath("$.hits[0].project_id").value("AANobbMI"));

        FakeHttpServer.Request request = FAKE.requests.stream().filter(r -> r.uri().startsWith("/modrinth/search")).reduce((a, b) -> b).orElseThrow();
        String uri = URLDecoder.decode(request.uri(), StandardCharsets.UTF_8);
        assertTrue(uri.contains("query=sod"), uri);
        assertTrue(uri.contains("[\"categories:fabric\"]"), uri);
        assertTrue(uri.contains("[\"versions:1.21.1\"]"), uri);
        assertTrue(uri.contains("server_side:required"), uri);
        assertEquals("titustesche/simplycraft/1.0", request.headers().get("User-agent").getFirst());
    }

    @Test
    void installsNewestReleaseWithRequiredDependencies() throws Exception {
        String id = fabricServer("Mods Modrinth");
        mvc.perform(post("/api/servers/{id}/modrinth/install", id).contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"AANobbMI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed.length()").value(2))
                .andExpect(jsonPath("$.installed[0].fileName").value("sodium-fabric-0.6.1.jar"))
                .andExpect(jsonPath("$.installed[0].modrinth.title").value("Sodium"))
                .andExpect(jsonPath("$.installed[1].modrinth.title").value("Fabric API"));

        var mods = servers.dir(id).resolve("mods");
        assertArrayEquals(SODIUM, Files.readAllBytes(mods.resolve("sodium-fabric-0.6.1.jar")));
        assertArrayEquals(FABRIC_API, Files.readAllBytes(mods.resolve("fabric-api-0.105.0.jar")));

        // Second install of the same mod: dependency already there
        mvc.perform(post("/api/servers/{id}/modrinth/install", id).contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"AANobbMI\"}"))
                .andExpect(jsonPath("$.installed.length()").value(1))
                .andExpect(jsonPath("$.skipped[0]").value("fabric-api-0.105.0.jar (bereits installiert)"));

        mvc.perform(get("/api/servers/{id}/mods", id))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].modrinth.projectId").value("AANobbMI"));
    }

    @Test
    void installNeedsALoader() throws Exception {
        String id = servers.create(new CreateServerRequest("Mods Vanilla", null, null, null)).getId();
        mvc.perform(post("/api/servers/{id}/modrinth/install", id).contentType(MediaType.APPLICATION_JSON).content("{\"projectId\":\"AANobbMI\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Mod-Loader")));
    }
}
