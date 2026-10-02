package de.titus.simplycraft.server;

import de.titus.simplycraft.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StartCommandTest {

    @Test
    void defaultCommandUsesMemorySettings() {
        ServerConfig config = new ServerConfig();
        config.setXms("2G");
        config.setXmx("6G");

        assertEquals(List.of("java", "-Xms2G", "-Xmx6G", "-jar", "server.jar", "nogui"),
                StartCommand.build(config, "java", "1G", "4G", true));
    }

    @Test
    void defaultsApplyWhenServerHasNoValues() {
        ServerConfig config = new ServerConfig();
        config.setJavaPath("/opt/java17/bin/java");

        assertEquals(List.of("/opt/java17/bin/java", "-Xms1G", "-Xmx4G", "-jar", "server.jar", "nogui"),
                StartCommand.build(config, "java", "1G", "4G", true));
    }

    @Test
    void customCommandIsSplitWithQuotesAndPlaceholders() {
        ServerConfig config = new ServerConfig();
        config.setXms("1G");
        config.setXmx("3G");
        config.setCustomCommand("{java} -Xmx{xmx} -Dname=\"my server\" -XX:+UseG1GC -jar {jar} --nogui");

        assertEquals(List.of("java", "-Xmx3G", "-Dname=my server", "-XX:+UseG1GC", "-jar", "server.jar", "--nogui"),
                StartCommand.build(config, "java", "1G", "4G", true));
    }

    @Test
    void customCommandIsIgnoredWhenDisabled() {
        ServerConfig config = new ServerConfig();
        config.setCustomCommand("rm -rf /");

        assertEquals("java", StartCommand.build(config, "java", "1G", "4G", false).getFirst());
        assertTrue(StartCommand.usesServerJar(config, false));
    }

    @Test
    void unclosedQuoteIsRejected() {
        assertThrows(ApiException.class, () -> StartCommand.tokenize("java \"-jar server.jar"));
    }

    @Test
    void detectsWhetherServerJarIsNeeded() {
        ServerConfig config = new ServerConfig();
        config.setCustomCommand("{java} -jar forge-launcher.jar");
        assertFalse(StartCommand.usesServerJar(config, true));

        config.setCustomCommand("{java} -jar {jar}");
        assertTrue(StartCommand.usesServerJar(config, true));
    }
}
