package de.titus.simplycraft.support;

import de.titus.simplycraft.config.ApiTokenFilter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared Spring context for all integration tests: temporary server/backup folders and one
 * fake HTTP server that stands in for Mojang, Fabric, Modrinth and Simplyfile.
 */
@SpringBootTest
public abstract class IntegrationTest {

    protected static final FakeHttpServer FAKE = new FakeHttpServer();
    protected static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("simplycraft-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("simplycraft.servers-path", () -> ROOT.resolve("servers").toString());
        registry.add("simplycraft.backups-path", () -> ROOT.resolve("backups").toString());
        registry.add("simplycraft.java-path", IntegrationTest::currentJava);
        registry.add("simplycraft.stop-timeout", () -> "3s");
        registry.add("simplycraft.simplyfile.url", () -> FAKE.url() + "/simplyfile");
        registry.add("simplycraft.modrinth.api-url", () -> FAKE.url() + "/modrinth");
        registry.add("simplycraft.sources.mojang-manifest", () -> FAKE.url() + "/mojang/manifest.json");
        registry.add("simplycraft.sources.fabric-meta", () -> FAKE.url() + "/fabric");
    }

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    protected ApiTokenFilter tokenFilter;

    protected MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(tokenFilter).build();
    }

    public static String currentJava() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Copies the fake Minecraft server into a server directory */
    protected static void installFakeServer(Path serverDir) {
        try (var in = IntegrationTest.class.getResourceAsStream("/fake-server/FakeServer.java")) {
            Files.createDirectories(serverDir);
            Files.copy(in, serverDir.resolve("FakeServer.java"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
