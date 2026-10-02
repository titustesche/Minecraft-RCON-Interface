package de.titus.simplycraft.process;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.server.CreateServerRequest;
import de.titus.simplycraft.server.ServerService;
import de.titus.simplycraft.server.UpdateServerRequest;
import de.titus.simplycraft.support.IntegrationTest;
import de.titus.simplycraft.support.Wait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Starts the fake Minecraft server (FakeServer.java) as a real child process */
class ServerProcessIntegrationTest extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ServerService servers;

    @Autowired
    ServerProcessManager processes;

    private final List<String> started = new ArrayList<>();

    @AfterEach
    void stopEverything() {
        for (String id : started) {
            if (processes.isRunning(id)) processes.stopAndWait(id);
        }
    }

    private String fakeServer(String name, boolean eula) {
        String id = servers.create(new CreateServerRequest(name, null, null, null)).getId();
        installFakeServer(servers.dir(id));
        servers.update(id, new UpdateServerRequest(null, null, null, null, null, "{java} -Xmx{xmx} FakeServer.java", eula, null, null));
        started.add(id);
        return id;
    }

    private void awaitState(String id, ServerState state) {
        Wait.until("state " + state, TIMEOUT, () -> processes.status(id).state() == state);
    }

    @Test
    void startStreamsLogsTracksPlayersAndStops() throws Exception {
        String id = fakeServer("Process Lifecycle", true);

        mvc.perform(post("/api/servers/{id}/start", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STARTING"));
        awaitState(id, ServerState.ONLINE);
        assertTrue(Files.readString(servers.dir(id).resolve("eula.txt")).contains("eula=true"));

        mvc.perform(post("/api/servers/{id}/command", id).contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"/join Steve\"}"))
                .andExpect(status().isAccepted());
        Wait.until("player Steve", TIMEOUT, () -> processes.status(id).players().stream().anyMatch(p -> p.name().equals("Steve")));

        var runtime = processes.runtime(id);
        assertTrue(runtime.backlog().stream().anyMatch(l -> l.level().equals(">") && l.text().equals("join Steve")));
        assertTrue(runtime.backlog().stream().anyMatch(l -> l.level().equals("WARN")));
        assertNotNull(processes.status(id).pid());

        mvc.perform(post("/api/servers/{id}/start", id)).andExpect(status().isConflict());

        mvc.perform(post("/api/servers/{id}/stop", id)).andExpect(status().isOk());
        awaitState(id, ServerState.OFFLINE);
        assertEquals(0, processes.status(id).exitCode());
        assertTrue(processes.status(id).players().isEmpty());
    }

    @Test
    void consoleStreamIsAnEventStream() throws Exception {
        String id = fakeServer("Process Stream", true);
        processes.start(id);
        awaitState(id, ServerState.ONLINE);

        var result = mvc.perform(get("/api/servers/{id}/console", id))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:status"), body);
        assertTrue(body.contains("event:backlog"), body);
        assertTrue(body.contains("Done (0.123s)"), body);
        result.getRequest().getAsyncContext().complete();
    }

    @Test
    void refusesToStartWithoutEulaOrJar() throws Exception {
        String id = fakeServer("Process No Eula", false);
        mvc.perform(post("/api/servers/{id}/start", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("EULA")));

        servers.update(id, new UpdateServerRequest(null, null, null, null, null, "", true, null, null));
        ApiException e = assertThrows(ApiException.class, () -> processes.start(id));
        assertTrue(e.getMessage().contains("server.jar"));
    }

    @Test
    void crashIsReported() {
        String id = fakeServer("Process Crash", true);
        processes.start(id);
        awaitState(id, ServerState.ONLINE);

        processes.command(id, "crash");
        awaitState(id, ServerState.CRASHED);
        assertEquals(3, processes.status(id).exitCode());
    }

    @Test
    void hangingServerIsKilledAfterStopTimeout() {
        String id = fakeServer("Process Hang", true);
        processes.start(id);
        awaitState(id, ServerState.ONLINE);

        processes.command(id, "ignore-stop");
        processes.stop(id);
        assertEquals(ServerState.STOPPING, processes.status(id).state());
        awaitState(id, ServerState.OFFLINE);
    }

    @Test
    void restartStartsAgain() {
        String id = fakeServer("Process Restart", true);
        processes.start(id);
        awaitState(id, ServerState.ONLINE);
        long firstPid = processes.status(id).pid();

        processes.restart(id);
        Wait.until("new process", TIMEOUT, () -> {
            var status = processes.status(id);
            return status.state() == ServerState.ONLINE && status.pid() != null && status.pid() != firstPid;
        });
    }
}
