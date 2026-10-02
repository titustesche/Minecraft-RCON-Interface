import {Api, enc} from "../api.js";
import {actionButton, busy, clear, Format, h, icon, iconButton, isRunning, Popup, STATE_LABEL} from "../ui.js";

const LEVEL_CLASS = {WARN: "warn", ERROR: "error", FATAL: "error", SEVERE: "error"};
const TINTS = ["var(--accent-color)", "var(--secondary-accent)", "var(--tertiary-accent)", "hsl(from var(--accent-color) calc(h + 60) s 40%)"];

export function ConsoleView({app, main, players}) {
    const id = app.current.config.id;
    const history = [];
    let historyIndex = -1;
    let filter = "";

    main.classList.add("console-view");

    let meta, terminal, input, startButton, restartButton, stopButton, killButton;
    clear(main,
        h("div", {class: "page-head"},
            h("div", {class: "page-head-title"},
                h("h1", {class: "title"}, "Konsole"),
                h("p", {class: "muted small", ref: el => meta = el})),
            h("div", {class: "button-row"},
                startButton = actionButton("play", "Start", e => control(e.currentTarget, "start"), {class: "tertiary"}),
                restartButton = actionButton("restart", "Neustart", e => control(e.currentTarget, "restart")),
                stopButton = actionButton("stop", "Stopp", e => control(e.currentTarget, "stop"), {class: "danger"}),
                killButton = actionButton("kill", "Beenden erzwingen", e => control(e.currentTarget, "kill"), {class: "danger hidden"}))),
        h("div", {class: "terminal", role: "log", "aria-live": "polite", "aria-label": "Serverausgabe", ref: el => terminal = el}),
        h("form", {class: "command-form", onSubmit: submit},
            h("label", {class: "field-pill"},
                h("span", {class: "prompt-sign", "aria-hidden": "true"}, ">"),
                h("span", {class: "visually-hidden"}, "Befehl"),
                h("input", {
                    type: "text", autocomplete: "off", spellcheck: "false",
                    placeholder: "Befehl eingeben … z. B. say Hallo, list, op <spieler>",
                    ref: el => input = el, onKeyDown: onKey,
                })),
            h("button", {type: "submit", class: "pill-button", "aria-label": "Senden"}, icon("send"), h("span", {class: "label"}, "Senden")))
    );

    // --- players aside ---------------------------------------------------------------------
    let playerCount, playerList;
    clear(players,
        h("div", {class: "split", style: "align-items: baseline"},
            h("p", {class: "title"}, "Spieler"),
            h("p", {class: "muted small", style: "margin: 0; font-variant-numeric: tabular-nums", ref: el => playerCount = el})),
        h("label", {class: "field-pill compact"},
            icon("search", 16),
            h("input", {type: "search", placeholder: "Spieler suchen", "aria-label": "Spieler suchen", onInput: e => { filter = e.target.value.trim().toLowerCase(); renderPlayers(); }})),
        h("div", {class: "row-list", style: "flex-grow: 1", ref: el => playerList = el})
    );

    function renderPlayers() {
        const status = app.status;
        const online = status?.players ?? [];
        playerCount.textContent = `${online.length} / ${status?.maxPlayers ?? "?"}`;
        const shown = online.filter(p => !filter || p.name.toLowerCase().includes(filter));
        clear(playerList, shown.length === 0
            ? h("p", {class: "empty-text"}, isRunning(status?.state) ? "Keine Spieler online" : "Server ist offline")
            : shown.map((p, i) => h("div", {class: "row"},
                h("div", {class: "avatar", style: `background-color: ${TINTS[online.indexOf(p) % TINTS.length]}`}, p.name.charAt(0).toUpperCase()),
                h("div", {style: "min-width: 0"},
                    h("p", {class: "row-title"}, p.name),
                    h("p", {class: "row-meta"}, "seit " + Format.duration(Math.round((Date.now() - new Date(p.joinedAt)) / 1000)))),
                h("div", {class: "row-actions"},
                    iconButton("mention", "Name in Befehl einfügen", () => {
                        input.value = (input.value ? input.value.replace(/\s*$/, " ") : "tell ") + p.name + " ";
                        input.focus();
                    }),
                    iconButton("kick", "Spieler kicken", e => busy(e.currentTarget, () => send(`kick ${p.name}`)), {class: "danger"})))));
    }

    // --- console lines ---------------------------------------------------------------------
    function lineElement(line) {
        const cls = ["line", LEVEL_CLASS[line.level], line.kind].filter(Boolean).join(" ");
        return h("div", {class: cls},
            h("span", {class: "line-time"}, line.time),
            h("span", {class: "line-level"}, line.level),
            h("span", {class: "line-text"}, line.text));
    }

    function nearBottom() {
        return terminal.scrollHeight - terminal.scrollTop - terminal.clientHeight < 60;
    }

    function renderAll(lines) {
        if (lines.length === 0) {
            clear(terminal, h("div", {class: "terminal-empty"},
                app.current.jarInstalled
                    ? "Noch keine Ausgabe. Starte den Server, um die Logs live zu sehen."
                    : "Es ist noch keine Serverdatei installiert. Wähle unter Einstellungen eine Version, einen Link oder lade eine .jar hoch."));
        } else {
            clear(terminal, lines.map(lineElement), h("div", {class: "terminal-cursor", "aria-hidden": "true"}, "▌"));
        }
        terminal.scrollTop = terminal.scrollHeight;
        renderMeta();
    }

    function appendLine(line) {
        const stick = nearBottom();
        if (terminal.querySelector(".terminal-empty")) return renderAll(app.stream.lines);
        const cursor = terminal.lastElementChild;
        terminal.insertBefore(lineElement(line), cursor);
        while (terminal.childElementCount > 2001) terminal.firstElementChild.remove();
        if (stick) terminal.scrollTop = terminal.scrollHeight;
        renderMeta();
    }

    function renderMeta() {
        const status = app.status;
        const uptime = status?.uptimeSeconds != null ? Format.duration(status.uptimeSeconds) : "–";
        meta.textContent = `${app.stream?.lines.length ?? 0} Zeilen · ${STATE_LABEL[status?.state ?? "OFFLINE"]} · Uptime ${uptime}`;
    }

    function renderControls() {
        const state = app.status?.state ?? "OFFLINE";
        const running = isRunning(state);
        startButton.disabled = running;
        restartButton.disabled = !running || state === "STOPPING";
        stopButton.disabled = !running || state === "STOPPING";
        killButton.classList.toggle("hidden", state !== "STOPPING");
    }

    // --- actions ---------------------------------------------------------------------------
    async function control(button, action) {
        await busy(button, async () => {
            await Api.post(`/api/servers/${enc(id)}/${action}`);
        });
        renderControls();
    }

    async function send(command) {
        await Api.post(`/api/servers/${enc(id)}/command`, {command});
    }

    async function submit(e) {
        e.preventDefault();
        const command = input.value.trim();
        if (!command) return;
        if (command === "clear") {
            input.value = "";
            app.stream.lines = [];
            return renderAll([]);
        }
        try {
            await send(command);
            history.push(command);
            if (history.length > 50) history.shift();
            historyIndex = -1;
            input.value = "";
        } catch (err) {
            Popup.error("Befehl nicht gesendet", err.message);
        }
    }

    function onKey(e) {
        if (e.key === "ArrowUp" && history.length) {
            e.preventDefault();
            historyIndex = Math.min(historyIndex + 1, history.length - 1);
            input.value = history[history.length - 1 - historyIndex];
        } else if (e.key === "ArrowDown" && historyIndex >= 0) {
            e.preventDefault();
            historyIndex--;
            input.value = historyIndex < 0 ? "" : history[history.length - 1 - historyIndex];
        }
    }

    // --- wiring ----------------------------------------------------------------------------
    renderAll(app.stream?.lines ?? []);
    renderControls();
    renderPlayers();
    input.focus();

    const off = [
        app.on("backlog", lines => renderAll(lines)),
        app.on("line", appendLine),
        app.on("status", () => { renderControls(); renderPlayers(); renderMeta(); }),
    ];
    return () => off.forEach(fn => fn());
}
