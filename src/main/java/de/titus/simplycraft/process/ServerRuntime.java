package de.titus.simplycraft.process;

import de.titus.simplycraft.common.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live state of one server process: the console backlog, the connected console streams
 * (SSE) and the players parsed from the log. Logs are not persisted here, Minecraft
 * already writes logs/latest.log.
 */
public class ServerRuntime {

    // [14:02:11] [Server thread/INFO]: text   (vanilla)   ...   [Server thread/INFO] (Minecraft) text   (fabric)
    private static final Pattern VANILLA_LINE = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?] \\[[^]]*/([A-Z]+)](?: \\([^)]*\\))?:? ?(.*)$");
    // [14:02:11 INFO]: text   (paper, spigot)
    private static final Pattern PAPER_LINE = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2}) ([A-Z]+)]:? ?(.*)$");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern JOINED = Pattern.compile("^([.\\w]{1,17}) joined the game");
    private static final Pattern LEFT = Pattern.compile("^([.\\w]{1,17}) left the game");
    private static final Pattern CHAT = Pattern.compile("^(?:\\[Not Secure] )?<[.\\w]{1,17}> ");
    private static final Pattern LIST = Pattern.compile("There are (\\d+) of a max(?: of)? (\\d+) players online:?(.*)$");
    private static final Pattern DONE = Pattern.compile("^Done \\(");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String id;
    private final int backlogSize;
    private final ArrayDeque<ConsoleLine> backlog = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> players = new LinkedHashMap<>();

    private volatile ServerState state = ServerState.OFFLINE;
    private volatile Process process;
    private volatile Writer stdin;
    private volatile Instant startedAt;
    private volatile Integer exitCode;
    private volatile Integer maxPlayers;
    private volatile boolean stopRequested;
    private long seq;

    public ServerRuntime(String id, int backlogSize) {
        this.id = id;
        this.backlogSize = Math.max(100, backlogSize);
    }

    public String id() {
        return id;
    }

    public ServerState state() {
        return state;
    }

    public Process process() {
        return process;
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    // --- lifecycle, called by ServerProcessManager --------------------------------------

    void started(Process process) {
        this.process = process;
        this.stdin = process.outputWriter();
        this.startedAt = Instant.now();
        this.exitCode = null;
        this.stopRequested = false;
        synchronized (players) {
            players.clear();
        }
        setState(ServerState.STARTING);
    }

    void stopping() {
        stopRequested = true;
        setState(ServerState.STOPPING);
    }

    void exited(int code) {
        exitCode = code;
        process = null;
        stdin = null;
        synchronized (players) {
            players.clear();
        }
        ServerState next = stopRequested || code == 0 ? ServerState.OFFLINE : ServerState.CRASHED;
        system(next == ServerState.CRASHED
                ? "Server wurde unerwartet beendet (Exit-Code " + code + ")"
                : "Server gestoppt (Exit-Code " + code + ")");
        setState(next);
    }

    /** Writes a command to the server's stdin */
    void send(String command) {
        Writer writer = stdin;
        if (writer == null || !isRunning()) throw ApiException.conflict("Der Server läuft nicht");
        try {
            synchronized (writer) {
                writer.write(command);
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            throw ApiException.conflict("Befehl konnte nicht gesendet werden: " + e.getMessage());
        }
    }

    // --- console ------------------------------------------------------------------------

    /** Raw line from the server process */
    public void output(String raw) {
        String line = ANSI.matcher(raw).replaceAll("");
        String time;
        String level;
        String text;
        Matcher m = VANILLA_LINE.matcher(line);
        if (!m.matches()) m = PAPER_LINE.matcher(line);
        if (m.matches()) {
            time = m.group(1);
            level = m.group(2);
            text = m.group(3);
        } else {
            time = now();
            level = "";
            text = line;
        }
        String kind = inspect(text);
        add(time, level, text, kind);
        notifyWaiters(text);
    }

    public void input(String command) {
        add(now(), ">", command, "cmd");
    }

    public void system(String message) {
        add(now(), "SYS", message, "sys");
    }

    public List<ConsoleLine> backlog() {
        synchronized (backlog) {
            return new ArrayList<>(backlog);
        }
    }

    private String inspect(String text) {
        Matcher m;
        if (DONE.matcher(text).find() && state == ServerState.STARTING) {
            setState(ServerState.ONLINE);
            return null;
        }
        if ((m = JOINED.matcher(text)).find()) {
            synchronized (players) {
                players.put(m.group(1), Instant.now());
            }
            return "join";
        }
        if ((m = LEFT.matcher(text)).find()) {
            synchronized (players) {
                players.remove(m.group(1));
            }
            return "leave";
        }
        if (CHAT.matcher(text).find()) return "chat";
        if ((m = LIST.matcher(text)).find()) {
            maxPlayers = Integer.parseInt(m.group(2));
            syncPlayers(m.group(3));
        }
        return null;
    }

    /** Takes the authoritative player list from the output of the "list" command */
    private void syncPlayers(String names) {
        synchronized (players) {
            Map<String, Instant> next = new LinkedHashMap<>();
            for (String name : names.split(",")) {
                String n = name.trim();
                if (n.isEmpty()) continue;
                next.put(n, players.getOrDefault(n, Instant.now()));
            }
            players.clear();
            players.putAll(next);
        }
    }

    private void add(String time, String level, String text, String kind) {
        ConsoleLine line;
        synchronized (backlog) {
            line = new ConsoleLine(++seq, time, level, text, kind);
            backlog.addLast(line);
            while (backlog.size() > backlogSize) backlog.removeFirst();
        }
        broadcast("line", line);
    }

    private void setState(ServerState next) {
        state = next;
        broadcast("status", status());
    }

    // --- status -------------------------------------------------------------------------

    public ServerStatus status() {
        Process p = process;
        Long pid = p != null ? p.pid() : null;
        Instant started = p != null ? startedAt : null;
        List<ServerStatus.Player> online;
        synchronized (players) {
            online = players.entrySet().stream().map(e -> new ServerStatus.Player(e.getKey(), e.getValue())).toList();
        }
        return new ServerStatus(
                id,
                state,
                pid,
                started,
                started != null ? Duration.between(started, Instant.now()).toSeconds() : null,
                pid != null ? residentMemory(pid) : null,
                online,
                maxPlayers,
                exitCode
        );
    }

    /** Resident memory of the process (Linux only, null elsewhere) */
    private static Long residentMemory(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return null;
    }

    // --- streams ------------------------------------------------------------------------

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        synchronized (this) {
            try {
                emitter.send(SseEmitter.event().name("status").data(status(), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().name("backlog").data(backlog(), MediaType.APPLICATION_JSON));
                emitters.add(emitter);
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        }
        return emitter;
    }

    public boolean hasSubscribers() {
        return !emitters.isEmpty();
    }

    void broadcast(String event, Object data) {
        if (emitters.isEmpty()) return;
        synchronized (this) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    // --- waiting for output (backups) ---------------------------------------------------

    /**
     * Starts listening for a line matching the predicate. Register before sending the command
     * that triggers the line, otherwise a fast answer is missed.
     */
    public Expectation expect(Predicate<String> predicate) {
        Waiter waiter = new Waiter(predicate, new CompletableFuture<>());
        waiters.add(waiter);
        return new Expectation(waiter);
    }

    public final class Expectation {
        private final Waiter waiter;

        private Expectation(Waiter waiter) {
            this.waiter = waiter;
        }

        /** Blocks until the line appeared or the timeout passed */
        public boolean await(Duration timeout) throws InterruptedException {
            try {
                waiter.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
                return false;
            } finally {
                waiters.remove(waiter);
            }
        }
    }

    private void notifyWaiters(String text) {
        for (Waiter waiter : waiters) {
            if (waiter.predicate().test(text)) waiter.future().complete(null);
        }
    }

    private record Waiter(Predicate<String> predicate, CompletableFuture<Void> future) {
    }

    private static String now() {
        return LocalTime.now().format(TIME);
    }
}
