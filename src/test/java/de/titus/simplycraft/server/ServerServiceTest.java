package de.titus.simplycraft.server;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.config.SimplycraftProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServerServiceTest {

    @TempDir
    Path root;

    private ServerService service;

    @BeforeEach
    void setUp() {
        service = new ServerService(properties(root, true), JsonMapper.builder().build());
    }

    public static SimplycraftProperties properties(Path root, boolean customCommands) {
        return new SimplycraftProperties(root.resolve("servers"), root.resolve("backups"), "java", "1G", "4G", 500,
                Duration.ofSeconds(5), "", customCommands, List.of(),
                new SimplycraftProperties.Simplyfile("", ""),
                new SimplycraftProperties.Modrinth("http://localhost", "test"),
                new SimplycraftProperties.Sources("http://localhost", "http://localhost"));
    }

    @Test
    void createsDirectoryConfigAndPort() throws Exception {
        ServerConfig config = service.create(new CreateServerRequest("Größte Welt!", 25570, null, "6g"));

        assertEquals("groesste-welt", config.getId());
        assertEquals("1G", config.getXms());
        assertEquals("6G", config.getXmx());
        Path dir = root.resolve("servers/groesste-welt");
        assertTrue(Files.isDirectory(dir.resolve("mods")));
        assertTrue(Files.isRegularFile(dir.resolve(ServerService.CONFIG_FILE)));
        assertEquals("server-port=25570", Files.readString(dir.resolve("server.properties")).trim());
        assertEquals("Größte Welt!", service.get("groesste-welt").getName());
    }

    @Test
    void idsAreUnique() {
        assertEquals("survival", service.create(new CreateServerRequest("Survival", null, null, null)).getId());
        assertEquals("survival-2", service.create(new CreateServerRequest("survival", null, null, null)).getId());
        assertEquals(2, service.list().size());
    }

    @Test
    void updateChangesPortWithoutTouchingOtherProperties() throws Exception {
        service.create(new CreateServerRequest("Test", null, null, null));
        Path properties = root.resolve("servers/test/server.properties");
        Files.writeString(properties, "motd=Hallo\nserver-port=25565\n");

        service.update("test", new UpdateServerRequest(null, 25600, "2G", "8G", null, "{java} -jar {jar}", true, "Fabric", "1.21.1"));

        assertEquals("motd=Hallo\nserver-port=25600", Files.readString(properties).trim());
        ServerConfig config = service.get("test");
        assertEquals("8G", config.getXmx());
        assertEquals("fabric", config.getLoader());
        assertEquals("1.21.1", config.getGameVersion());
        assertTrue(config.isEulaAccepted());
        assertEquals("{java} -jar {jar}", config.getCustomCommand());

        service.update("test", new UpdateServerRequest(null, null, null, null, null, "", null, "", null));
        assertNull(service.get("test").getCustomCommand());
        assertNull(service.get("test").getLoader());
    }

    @Test
    void rejectsInvalidMemoryAndXmsAboveXmx() {
        service.create(new CreateServerRequest("Mem", null, null, null));
        assertThrows(ApiException.class, () -> service.update("mem", new UpdateServerRequest(null, null, "lots", null, null, null, null, null, null)));
        assertThrows(ApiException.class, () -> service.update("mem", new UpdateServerRequest(null, null, "8G", "2G", null, null, null, null, null)));
    }

    @Test
    void customCommandCanBeDisabled() {
        var locked = new ServerService(properties(root.resolve("locked"), false), JsonMapper.builder().build());
        locked.create(new CreateServerRequest("Locked", null, null, null));
        assertThrows(ApiException.class, () -> locked.update("locked", new UpdateServerRequest(null, null, null, null, null, "sh -c evil", null, null, null)));
    }

    @Test
    void unknownOrMaliciousIdsAreNotFound() {
        assertThrows(ApiException.class, () -> service.get("../etc"));
        assertThrows(ApiException.class, () -> service.get("missing"));
    }

    @Test
    void deleteRemovesDirectory() throws Exception {
        service.create(new CreateServerRequest("Weg", null, null, null));
        service.delete("weg");
        assertFalse(Files.exists(root.resolve("servers/weg")));
    }
}
