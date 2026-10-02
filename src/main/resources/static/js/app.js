import {Api, enc} from "./api.js";
import {clear, Format, h, icon, isRunning, modal, Popup, STATE_LABEL, stateClass} from "./ui.js";
import {ConsoleView} from "./views/console.js";
import {ModsView} from "./views/mods.js";
import {BackupsView} from "./views/backups.js";
import {SettingsView} from "./views/settings.js";
import {CreateView} from "./views/create.js";

const VIEWS = {
    console: {label: "Konsole", icon: "console", view: ConsoleView},
    mods: {label: "Mods", icon: "mods", view: ModsView},
    backups: {label: "Backups", icon: "backup", view: BackupsView},
    settings: {label: "Einstellungen", icon: "settings", view: SettingsView},
};

const MAX_LINES = 2000;

/**
 * Live connection to one server: console lines and status via server-sent events.
 * EventSource reconnects on its own and then gets the backlog again.
 */
class ConsoleStream {
    constructor(id, emit) {
        this.id = id;
        this.emit = emit;
        this.lines = [];
        this.status = null;
        this.source = new EventSource(Api.url(`/api/servers/${enc(id)}/console`));
        this.source.addEventListener("status", e => {
            this.status = JSON.parse(e.data);
            this.emit("status", this.status);
        });
        this.source.addEventListener("backlog", e => {
            this.lines = JSON.parse(e.data);
            this.emit("backlog", this.lines);
        });
        this.source.addEventListener("line", e => {
            const line = JSON.parse(e.data);
            this.lines.push(line);
            if (this.lines.length > MAX_LINES) this.lines.splice(0, this.lines.length - MAX_LINES);
            this.emit("line", line);
        });
        this.source.onerror = () => this.emit("disconnected");
    }

    close() {
        this.source.close();
    }
}

export const App = {
    info: {},
    servers: [],
    current: null,
    viewName: null,
    stream: null,
    cleanup: null,
    listeners: new Map(),

    on(event, fn) {
        if (!this.listeners.has(event)) this.listeners.set(event, new Set());
        this.listeners.get(event).add(fn);
        return () => this.listeners.get(event)?.delete(fn);
    },

    emit(event, data) {
        for (const fn of this.listeners.get(event) ?? []) fn(data);
    },

    get status() {
        return this.stream?.status ?? this.current?.status ?? null;
    },

    go(id, view = "console") {
        location.hash = id ? `#/${id}/${view}` : "#/new";
    },

    async loadServers() {
        this.servers = await Api.get("/api/servers");
        if (this.current) {
            const fresh = this.servers.find(s => s.config.id === this.current.config.id);
            if (fresh) this.current = fresh;
        }
        renderSidebar();
        return this.servers;
    },

    /** Replaces the current server's data after an update (settings, jar install) */
    setCurrent(view) {
        this.current = view;
        const index = this.servers.findIndex(s => s.config.id === view.config.id);
        if (index >= 0) this.servers[index] = view;
        renderHeader();
        renderSidebar();
    },

    async route() {
        const [, id, view] = /^#\/([^/]*)\/?([^/]*)?/.exec(location.hash) ?? [];
        if (id === "new" || (!id && this.servers.length === 0)) return this.show(null, "new");

        const server = this.servers.find(s => s.config.id === id) ?? this.servers[0];
        if (!server) return this.show(null, "new");
        const viewName = VIEWS[view] ? view : "console";
        if (server.config.id !== id || viewName !== view) {
            history.replaceState(null, "", `#/${server.config.id}/${viewName}`);
        }
        return this.show(server, viewName);
    },

    show(server, viewName) {
        this.cleanup?.();
        this.cleanup = null;

        if (server?.config.id !== this.current?.config.id || !server) {
            this.stream?.close();
            this.stream = server ? new ConsoleStream(server.config.id, (event, data) => this.emit(event, data)) : null;
        }
        this.current = server;
        this.viewName = viewName;

        const main = document.getElementById("main");
        const players = document.getElementById("players");
        main.className = "main panel";
        clear(main);
        clear(players);
        document.getElementById("layout-grid").classList.toggle("no-players", viewName !== "console");

        const View = viewName === "new" ? CreateView : VIEWS[viewName].view;
        this.cleanup = View({app: this, main, players}) ?? null;
        renderHeader();
        renderSidebar();
    },
};

// --- Header & sidebar ----------------------------------------------------------------------

function renderHeader() {
    const name = App.current?.config.name;
    document.getElementById("header-server").textContent = name ? `/ ${name}` : "";
    document.title = name ? `${name} · Serverkonsole` : "Serverkonsole";
}

function renderSidebar() {
    const sidebar = document.getElementById("sidebar");
    const current = App.current;
    const status = App.status;

    const nav = Object.entries(VIEWS).map(([key, v]) =>
        h("button", {
            type: "button",
            class: "nav-button" + (App.viewName === key ? " active" : ""),
            disabled: !current,
            "aria-current": App.viewName === key ? "page" : null,
            onClick: () => App.go(current.config.id, key),
        }, icon(v.icon), h("span", null, v.label)));

    const servers = App.servers.map(s => {
        const state = s.config.id === current?.config.id ? status?.state : s.status?.state;
        return h("button", {
            type: "button",
            class: "nav-button" + (s.config.id === current?.config.id ? " active" : ""),
            onClick: () => App.go(s.config.id, App.viewName && App.viewName !== "new" ? App.viewName : "console"),
            title: s.config.name,
        }, icon("server"), h("span", null, s.config.name), h("span", {class: "dot " + stateClass(state), title: STATE_LABEL[state] ?? ""}));
    });

    clear(sidebar,
        h("p", {class: "title"}, "Menü"),
        h("div", {class: "nav"}, nav),
        h("p", {class: "nav-label"}, "Server"),
        h("div", {class: "nav"}, servers,
            h("button", {type: "button", class: "nav-button" + (App.viewName === "new" ? " active" : ""), onClick: () => App.go(null)},
                icon("plus"), h("span", null, "Neuer Server"))),
        h("div", {class: "sidebar-bottom"}, current ? statusBox(current, status) : null)
    );
}

function statusBox(server, status) {
    const state = status?.state ?? "OFFLINE";
    const xmx = Format.memory(server.config.xmx ?? App.info.defaultXmx);
    const used = status?.memoryBytes ?? 0;
    const pct = xmx > 0 ? Math.min(100, Math.round(used / xmx * 100)) : 0;
    const jar = server.config.jar;
    const version = server.config.gameVersion ?? (jar ? (jar.source === "UPLOAD" ? "eigene Datei" : "eigener Link") : "keine Serverdatei");
    const loader = server.config.loader && server.config.loader !== "vanilla" ? ` · ${server.config.loader}` : "";

    return h("div", {class: "status-box " + stateClass(state)},
        h("div", {class: "status-head"},
            h("span", {class: "status-name"}, server.config.name),
            h("span", {class: "status-state"}, h("span", {class: "dot " + stateClass(state)}), STATE_LABEL[state])),
        h("p", {class: "muted"}, `Version ${version}${loader}`),
        h("p", {class: "muted"}, `Port ${server.config.port ?? "25565 (Standard)"}`),
        h("div", {class: "split", style: "margin-top: 0.25rem"},
            h("span", {class: "muted"}, "RAM"),
            h("span", {style: "font-variant-numeric: tabular-nums"},
                isRunning(state) && status?.memoryBytes != null ? `${Format.bytes(used)} / ${server.config.xmx}` : `– / ${server.config.xmx}`)),
        h("div", {class: "meter", role: "progressbar", "aria-valuenow": pct, "aria-valuemin": 0, "aria-valuemax": 100, "aria-label": "Arbeitsspeicher"},
            h("div", {style: `width: ${isRunning(state) ? pct : 0}%`}))
    );
}

// --- Accent color (same behaviour and storage key as Simplyfile) ----------------------------

const DEFAULT_ACCENT = "#0070ff";

function setupAccentColor() {
    const picker = document.getElementById("accent-color-picker");
    let color = DEFAULT_ACCENT;
    try { color = localStorage.getItem("accent-color") ?? DEFAULT_ACCENT; } catch { /* storage blocked */ }
    document.documentElement.style.setProperty("--accent-color", color);
    picker.value = color;
    picker.addEventListener("input", () => {
        document.documentElement.style.setProperty("--accent-color", picker.value);
        try { localStorage.setItem("accent-color", picker.value); } catch { /* storage blocked */ }
    });
}

// --- Token ---------------------------------------------------------------------------------

let tokenPrompt = null;

Api.onUnauthorized = () => {
    tokenPrompt ??= (async () => {
        let input;
        const token = await modal("API-Token", h("label", {class: "field"},
            h("span", null, "Dieser Server ist geschützt. Bitte gib den API-Token ein (simplycraft.api-token)."),
            h("input", {type: "password", autocomplete: "off", ref: el => input = el})), [
            {label: "Abbrechen"},
            {label: "Anmelden", primary: true, value: () => input.value.trim()},
        ]);
        tokenPrompt = null;
        if (!token) return false;
        Api.setToken(token);
        // The console stream was opened without (valid) token
        if (App.current) App.show(App.current, App.viewName);
        return true;
    })();
    return tokenPrompt;
};

// --- Start -----------------------------------------------------------------------------------

window.addEventListener("load", async () => {
    setupAccentColor();

    if (!await Api.reachable()) {
        await modal("Keine Verbindung zum Backend", h("p", null, `${Api.base} ist nicht erreichbar.`), [
            {label: "Neu laden", primary: true, value: () => location.reload()},
        ]);
        return;
    }

    try {
        App.info = await Api.get("/api/info");
        if (App.info.authRequired && !Api.token) await Api.onUnauthorized();
        await App.loadServers();
    } catch (e) {
        Popup.error("Laden fehlgeschlagen", e.message);
    }

    App.on("status", status => {
        const server = App.servers.find(s => s.config.id === status.id);
        if (server) server.status = status;
        renderSidebar();
    });

    window.addEventListener("hashchange", () => App.route());
    await App.route();

    // Keep the dots of the other servers fresh
    setInterval(() => App.loadServers().catch(() => {}), 15000);
});
