package de.titus.simplycraft.process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeTest {

    @Test
    void parsesVanillaFabricAndPaperLines() {
        ServerRuntime runtime = new ServerRuntime("test", 100);
        runtime.output("[14:02:11] [Server thread/INFO]: Starting minecraft server version 1.21.1");
        runtime.output("[14:02:12] [Server thread/WARN] (Minecraft) Can't keep up!");
        runtime.output("[14:02:13 ERROR]: Could not save chunk");
        runtime.output("\u001B[32mjust some text\u001B[0m");

        List<ConsoleLine> lines = runtime.backlog();
        assertEquals(new ConsoleLine(1, "14:02:11", "INFO", "Starting minecraft server version 1.21.1", null), lines.get(0));
        assertEquals("WARN", lines.get(1).level());
        assertEquals("Can't keep up!", lines.get(1).text());
        assertEquals("ERROR", lines.get(2).level());
        assertEquals("Could not save chunk", lines.get(2).text());
        assertEquals("", lines.get(3).level());
        assertEquals("just some text", lines.get(3).text());
    }

    @Test
    void tracksPlayersAndChat() {
        ServerRuntime runtime = new ServerRuntime("test", 100);
        runtime.output("[14:02:41] [Server thread/INFO]: kaktus_kai joined the game");
        runtime.output("[14:02:58] [Server thread/INFO]: Lumen joined the game");
        runtime.output("[14:05:02] [Server thread/INFO]: <Lumen> hat jemand Eisen übrig?");
        runtime.output("[14:06:00] [Server thread/INFO]: kaktus_kai left the game");

        assertEquals(List.of("Lumen"), runtime.status().players().stream().map(ServerStatus.Player::name).toList());
        List<ConsoleLine> lines = runtime.backlog();
        assertEquals("join", lines.get(0).kind());
        assertEquals("chat", lines.get(2).kind());
        assertEquals("leave", lines.get(3).kind());
    }

    @Test
    void listOutputResynchronisesPlayers() {
        ServerRuntime runtime = new ServerRuntime("test", 100);
        runtime.output("[14:02:41] [Server thread/INFO]: Ghost joined the game");
        runtime.output("[14:10:00] [Server thread/INFO]: There are 2 of a max of 10 players online: Steve, Alex");

        ServerStatus status = runtime.status();
        assertEquals(List.of("Steve", "Alex"), status.players().stream().map(ServerStatus.Player::name).toList());
        assertEquals(10, status.maxPlayers());
    }

    @Test
    void backlogIsBounded() {
        ServerRuntime runtime = new ServerRuntime("test", 100);
        for (int i = 0; i < 250; i++) runtime.output("line " + i);
        List<ConsoleLine> lines = runtime.backlog();
        assertEquals(100, lines.size());
        assertEquals("line 249", lines.getLast().text());
        assertEquals(250, lines.getLast().seq());
    }

    @Test
    void expectationCatchesLinesThatArriveBeforeAwait() throws Exception {
        ServerRuntime runtime = new ServerRuntime("test", 100);
        var saved = runtime.expect(line -> line.contains("Saved the game"));
        runtime.output("[14:10:00] [Server thread/INFO]: Saved the game");

        assertTrue(saved.await(java.time.Duration.ofMillis(10)));
        assertFalse(runtime.expect(line -> line.contains("never")).await(java.time.Duration.ofMillis(10)));
    }
}
