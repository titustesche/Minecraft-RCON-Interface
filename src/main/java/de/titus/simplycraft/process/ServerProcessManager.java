package de.titus.simplycraft.process;

import de.titus.simplycraft.common.ApiException;
import de.titus.simplycraft.config.SimplycraftProperties;
import de.titus.simplycraft.server.ServerConfig;
import de.titus.simplycraft.server.ServerService;
import de.titus.simplycraft.server.StartCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Starts the server jars as child processes of the Spring application. Every server gets
 * its own console thread that pumps stdout into its {@link ServerRuntime}; commands go to stdin.
 */
@Service
public class ServerProcessManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ServerProcessManager.class);

    private final ServerService servers;
    private final SimplycraftProperties properties;
    private final Map<String, ServerRuntime> runtimes = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public ServerProcessManager(ServerService servers, SimplycraftProperties properties) {
        this.servers = servers;
        this.properties = properties;
    }

    public ServerRuntime runtime(String id) {
        servers.dir(id);
        return runtimes.computeIfAbsent(id, key -> new ServerRuntime(key, properties.consoleBacklog()));
    }

    public ServerStatus status(String id) {
        return runtime(id).status();
    }

    public boolean isRunning(String id) {
        ServerRuntime runtime = runtimes.get(id);
        return runtime != null && runtime.isRunning();
    }

    public void requireStopped(String id, String action) {
        if (isRunning(id)) throw ApiException.conflict("Bitte den Server stoppen, bevor du " + action);
    }

    public ServerStatus start(String id) {
        ServerConfig config = servers.get(id);
        ServerRuntime runtime = runtime(id);
        synchronized (runtime) {
            if (runtime.isRunning()) throw ApiException.conflict("Der Server läuft bereits");
            if (!config.isEulaAccepted()) {
                throw ApiException.badRequest("Bitte akzeptiere zuerst die Minecraft-EULA in den Einstellungen des Servers");
            }
            Path dir = servers.dir(id);
            if (StartCommand.usesServerJar(config, properties.customCommandEnabled()) && !Files.isRegularFile(servers.jar(id))) {
                throw ApiException.badRequest("Es ist noch keine server.jar installiert");
            }

            List<String> command = StartCommand.build(config, properties.javaPath(), properties.defaultXms(),
                    properties.defaultXmx(), properties.customCommandEnabled());
            Process process;
            try {
                Files.writeString(dir.resolve("eula.txt"), "# Accepted in Simplycraft (https://aka.ms/MinecraftEULA)\neula=true\n");
                process = new ProcessBuilder(command)
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException e) {
                runtime.system("Start fehlgeschlagen: " + e.getMessage());
                throw ApiException.badRequest("Start fehlgeschlagen: " + e.getMessage());
            }

            runtime.started(process);
            runtime.system("Starte: " + String.join(" ", command));
            log.info("Started server {} (pid {})", id, process.pid());

            Thread.ofPlatform()
                    .name("mc-" + id + "-console")
                    .daemon(true)
                    .start(() -> pump(runtime, process));
        }
        return runtime.status();
    }

    private void pump(ServerRuntime runtime, Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) runtime.output(line);
        } catch (IOException e) {
            log.debug("Console of {} closed: {}", runtime.id(), e.getMessage());
        }
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        log.info("Server {} exited with code {}", runtime.id(), code);
        runtime.exited(code);
    }

    public void command(String id, String command) {
        if (command == null || command.isBlank()) throw ApiException.badRequest("Befehl fehlt");
        String cmd = command.strip().replace("\n", " ").replace("\r", " ");
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        ServerRuntime runtime = runtime(id);
        runtime.send(cmd);
        runtime.input(cmd);
        if (cmd.equalsIgnoreCase("stop")) runtime.stopping();
    }

    /** Sends "stop" and kills the process if it is still alive after the stop timeout. Returns immediately. */
    public ServerStatus stop(String id) {
        ServerRuntime runtime = runtime(id);
        Process process = requestStop(runtime);
        background.submit(() -> awaitExit(runtime, process));
        return runtime.status();
    }

    public void stopAndWait(String id) {
        ServerRuntime runtime = runtime(id);
        if (!runtime.isRunning()) return;
        awaitExit(runtime, requestStop(runtime));
    }

    public ServerStatus restart(String id) {
        ServerRuntime runtime = runtime(id);
        Process process = requestStop(runtime);
        background.submit(() -> {
            awaitExit(runtime, process);
            try {
                start(id);
            } catch (ApiException e) {
                runtime.system("Neustart fehlgeschlagen: " + e.getMessage());
            }
        });
        return runtime.status();
    }

    public ServerStatus kill(String id) {
        ServerRuntime runtime = runtime(id);
        Process process = runtime.process();
        if (process == null || !process.isAlive()) throw ApiException.conflict("Der Server läuft nicht");
        runtime.stopping();
        runtime.system("Prozess wird hart beendet");
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        return runtime.status();
    }

    private Process requestStop(ServerRuntime runtime) {
        Process process = runtime.process();
        if (process == null || !process.isAlive()) throw ApiException.conflict("Der Server läuft nicht");
        runtime.stopping();
        try {
            runtime.send("stop");
            runtime.input("stop");
        } catch (ApiException e) {
            process.destroy();
        }
        return process;
    }

    private void awaitExit(ServerRuntime runtime, Process process) {
        Duration timeout = properties.stopTimeout();
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return;
            runtime.system("Server reagiert nicht nach " + timeout.toSeconds() + " s, Prozess wird beendet");
            process.destroy();
            if (process.waitFor(10, TimeUnit.SECONDS)) return;
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keeps open console streams alive and refreshes uptime, memory and players */
    @Scheduled(fixedDelay = 5000)
    public void heartbeat() {
        for (ServerRuntime runtime : runtimes.values()) {
            if (runtime.hasSubscribers()) runtime.broadcast("status", runtime.status());
        }
    }

    /** Stops all servers cleanly when the application shuts down */
    @Override
    public void destroy() {
        var running = runtimes.values().stream().filter(ServerRuntime::isRunning).toList();
        if (running.isEmpty()) {
            background.shutdownNow();
            return;
        }
        log.info("Stopping {} running server(s)", running.size());
        var stops = running.stream().map(runtime -> background.submit(() -> stopAndWait(runtime.id()))).toList();
        for (var stop : stops) {
            try {
                stop.get(properties.stopTimeout().toSeconds() + 30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Server did not stop cleanly: {}", e.getMessage());
            }
        }
        background.shutdownNow();
    }
}
