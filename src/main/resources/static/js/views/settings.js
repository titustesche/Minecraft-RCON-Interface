import {Api, enc} from "../api.js";
import {busy, clear, confirm, dropzone, Format, h, icon, isRunning, Popup} from "../ui.js";

const DEFAULT_COMMAND = "{java} -Xms{xms} -Xmx{xmx} -jar {jar} nogui";
const SOURCE_LABEL = {VANILLA: "Vanilla", FABRIC: "Fabric", URL: "Link", UPLOAD: "Upload"};
const LOADERS = ["vanilla", "fabric", "quilt", "forge", "neoforge", "paper", "purpur", "spigot", "bukkit"];

export function SettingsView({app, main}) {
    const id = app.current.config.id;
    const base = `/api/servers/${enc(id)}`;
    let disposed = false;
    let jarSection, startForm;

    clear(main,
        h("div", {class: "page-head"}, h("h1", {class: "title"}, "Einstellungen")),
        jarSection = h("section", {class: "section", "aria-label": "Serverdatei"}),
        startForm = h("form", {class: "section", onSubmit: save}),
        dangerZone());

    const running = () => isRunning(app.status?.state);
    const field = (label, input, hint) => h("label", {class: "field"}, h("span", null, label), input, hint ? h("span", {class: "small muted"}, hint) : null);

    // --- server jar --------------------------------------------------------------------------
    let tab = "version";

    function renderJar() {
        const config = app.current.config;
        const jar = config.jar;
        const current = jar
            ? [`${SOURCE_LABEL[jar.source] ?? jar.source}${jar.version ? " " + jar.version : ""}`, Format.bytes(jar.size), `installiert ${Format.date(jar.installedAt)}`].join(" · ")
            : "Noch keine Serverdatei installiert";

        let body;
        clear(jarSection,
            h("h2", null, "Serverdatei"),
            h("p", {class: "small"}, h("code", null, `servers/${id}/server.jar`), " ", h("span", {class: "muted"}, current)),
            jar?.origin ? h("p", {class: "small muted", style: "overflow-wrap: anywhere"}, "Quelle: ", jar.origin) : null,
            running() ? h("p", {class: "warn-text small"}, "Stoppe den Server, um die Serverdatei auszutauschen.") : null,
            h("div", {class: "tabs", role: "tablist"},
                [["version", "Version wählen"], ["url", "Link eingeben"], ["upload", "Datei hochladen"]].map(([key, label]) =>
                    h("button", {type: "button", role: "tab", class: "tab" + (tab === key ? " active" : ""), "aria-selected": String(tab === key),
                        onClick: () => { tab = key; renderJar(); }}, label))),
            body = h("div", {style: "display: flex; flex-direction: column; gap: 0.75rem"}));

        if (tab === "version") versionTab(body);
        else if (tab === "url") urlTab(body);
        else uploadTab(body);
    }

    function versionTab(body) {
        let type, snapshots, version, button;
        const loadVersions = async () => {
            clear(version, h("option", {value: ""}, "Lade Versionen …"));
            try {
                const versions = await Api.get(`/api/versions?type=${type.value}&snapshots=${snapshots.checked}`);
                if (disposed) return;
                clear(version, versions.map(v => h("option", {value: v.id, selected: v.id === app.current.config.gameVersion}, v.id + (v.type === "snapshot" ? " (Snapshot)" : ""))));
            } catch (e) {
                clear(version, h("option", {value: ""}, "Versionen nicht verfügbar"));
                Popup.error("Versionsliste nicht erreichbar", e.message);
            }
        };
        clear(body,
            h("div", {class: "form-grid"},
                field("Typ", type = h("select", {onChange: loadVersions},
                    h("option", {value: "VANILLA"}, "Vanilla (Mojang)"),
                    h("option", {value: "FABRIC", selected: app.current.config.loader === "fabric"}, "Fabric (Mods)"))),
                field("Version", version = h("select")),
                h("label", {class: "check small", style: "align-self: end; min-height: 2.6rem"},
                    snapshots = h("input", {type: "checkbox", onChange: loadVersions}), "Snapshots anzeigen")),
            h("p", {class: "small muted"}, "Fabric installiert den Fabric-Launcher als server.jar; er lädt beim ersten Start den passenden Vanilla-Server nach."),
            h("div", {class: "button-row"},
                button = h("button", {type: "button", class: "pill-button primary", disabled: running(), onClick: () => busy(button, async () => {
                    if (!version.value) throw new Error("Bitte eine Version wählen");
                    button.replaceChildren(h("span", {class: "spinner"}), "Lädt herunter …");
                    try {
                        installed(await Api.post(`${base}/jar/version`, {type: type.value, version: version.value}));
                    } finally {
                        button.replaceChildren(icon("download"), "Installieren");
                    }
                })}, icon("download"), "Installieren")));
        loadVersions();
    }

    function urlTab(body) {
        let url, button;
        clear(body,
            field("Download-Link zur Server-.jar", url = h("input", {type: "url", placeholder: "https://…/server.jar", autocomplete: "off"}),
                "Z. B. Paper, Purpur, Forge-Serverlauncher. Trage danach unten den Loader und die Minecraft-Version ein, damit der Mod-Browser passend filtert."),
            h("div", {class: "button-row"},
                button = h("button", {type: "button", class: "pill-button primary", disabled: running(), onClick: () => busy(button, async () => {
                    if (!url.value.trim()) throw new Error("Bitte einen Link eingeben");
                    button.replaceChildren(h("span", {class: "spinner"}), "Lädt herunter …");
                    try {
                        installed(await Api.post(`${base}/jar/url`, {url: url.value.trim()}));
                    } finally {
                        button.replaceChildren(icon("link"), "Herunterladen");
                    }
                })}, icon("link"), "Herunterladen")));
    }

    function uploadTab(body) {
        let progress;
        clear(body,
            dropzone({
                title: "Server-.jar hierher ziehen",
                hint: "oder klicken · wird als server.jar gespeichert",
                accept: ".jar",
                multiple: false,
                onFiles: async ([file]) => {
                    if (running()) return Popup.warn("Server läuft", "Stoppe den Server, um die Serverdatei auszutauschen.");
                    const form = new FormData();
                    form.append("file", file);
                    progress.classList.remove("hidden");
                    try {
                        installed(await Api.upload(`${base}/jar/upload`, form, p => progress.firstElementChild.style.width = `${Math.round(p * 100)}%`));
                    } catch (e) {
                        Popup.error("Upload fehlgeschlagen", e.message);
                    } finally {
                        progress.classList.add("hidden");
                    }
                },
            }),
            progress = h("div", {class: "progress-line hidden"}, h("div")));
    }

    function installed(view) {
        app.setCurrent(view);
        Popup.info("Serverdatei installiert", Format.bytes(view.config.jar?.size));
        renderJar();
        renderStart();
    }

    // --- start configuration -----------------------------------------------------------------
    function renderStart() {
        const c = app.current.config;
        const info = app.info;
        let command, preview;
        const updatePreview = () => {
            const xms = startForm.elements.xms.value.trim() || info.defaultXms;
            const xmx = startForm.elements.xmx.value.trim() || info.defaultXmx;
            const java = startForm.elements.javaPath.value.trim() || "java";
            const template = (command?.value.trim() || DEFAULT_COMMAND);
            preview.textContent = template.replaceAll("{java}", java).replaceAll("{xms}", xms).replaceAll("{xmx}", xmx).replaceAll("{jar}", "server.jar");
        };

        clear(startForm,
            h("h2", null, "Start"),
            h("div", {class: "form-grid"},
                field("Name", h("input", {name: "name", value: c.name, required: true, maxlength: 48})),
                field("Port", h("input", {name: "port", type: "number", min: 1, max: 65535, value: c.port ?? "", placeholder: "25565"})),
                field("Xms (Start-RAM)", h("input", {name: "xms", value: c.xms ?? "", placeholder: info.defaultXms, onInput: () => updatePreview()})),
                field("Xmx (Max-RAM)", h("input", {name: "xmx", value: c.xmx ?? "", placeholder: info.defaultXmx, onInput: () => updatePreview()})),
                field("Java", h("input", {name: "javaPath", value: c.javaPath ?? "", placeholder: "java (Standard)", onInput: () => updatePreview()}),
                    "Pfad zu einer anderen Java-Version, falls nötig")),
            info.customCommandEnabled
                ? field("Eigener Startbefehl (optional)",
                    command = h("textarea", {name: "customCommand", rows: 2, spellcheck: "false", placeholder: DEFAULT_COMMAND, value: c.customCommand ?? "", onInput: () => updatePreview()}),
                    "Platzhalter: {java} {xms} {xmx} {jar}. Leer lassen für den Standard. Wird ohne Shell ausgeführt.")
                : null,
            h("p", {class: "small"}, h("span", {class: "muted"}, "Startet mit: "), preview = h("code")),
            h("h2", {style: "margin-top: 0.5rem"}, "Mods"),
            h("div", {class: "form-grid"},
                field("Loader", h("select", {name: "loader"},
                    h("option", {value: ""}, "– unbekannt –"),
                    LOADERS.map(l => h("option", {value: l, selected: c.loader === l}, l)))),
                field("Minecraft-Version", h("input", {name: "gameVersion", value: c.gameVersion ?? "", placeholder: "z. B. 1.21.1"}),
                    "Filtert den Modrinth-Browser")),
            h("label", {class: "check"},
                h("input", {type: "checkbox", name: "eulaAccepted", checked: c.eulaAccepted}),
                h("span", null, "Ich akzeptiere die ", h("a", {href: "https://aka.ms/MinecraftEULA", target: "_blank", rel: "noopener", class: "accent-text"}, "Minecraft-EULA"))),
            h("div", {class: "button-row"},
                h("button", {type: "submit", class: "pill-button primary"}, icon("check"), "Speichern"),
                running() ? h("span", {class: "small muted"}, "Änderungen gelten ab dem nächsten Start.") : null));
        updatePreview();
    }

    async function save(e) {
        e.preventDefault();
        const f = startForm.elements;
        const body = {
            name: f.name.value.trim(),
            port: f.port.value ? Number(f.port.value) : null,
            xms: f.xms.value.trim() || app.info.defaultXms,
            xmx: f.xmx.value.trim() || app.info.defaultXmx,
            javaPath: f.javaPath.value.trim(),
            loader: f.loader.value,
            gameVersion: f.gameVersion.value.trim(),
            eulaAccepted: f.eulaAccepted.checked,
        };
        if (f.customCommand) body.customCommand = f.customCommand.value.trim();
        await busy(e.submitter, async () => {
            app.setCurrent(await Api.patch(base, body));
            Popup.info("Gespeichert", "Die Einstellungen wurden übernommen");
            renderStart();
        });
    }

    // --- delete ----------------------------------------------------------------------------
    function dangerZone() {
        let button;
        return h("section", {class: "section", style: "border-color: var(--danger-color)"},
            h("h2", {class: "danger-text"}, "Server löschen"),
            h("p", {class: "small muted"}, "Löscht den kompletten Serverordner inklusive Welten und Mods. Backups bleiben erhalten."),
            h("div", {class: "button-row"},
                button = h("button", {type: "button", class: "action-button danger", onClick: async () => {
                    if (!await confirm("Server löschen", `„${app.current.config.name}“ und alle Dateien in servers/${id}/ werden endgültig gelöscht.`, "Endgültig löschen")) return;
                    await busy(button, async () => {
                        await Api.del(base);
                        Popup.info("Gelöscht", app.current.config.name);
                        app.current = null;
                        await app.loadServers();
                        app.go(app.servers[0]?.config.id ?? null);
                    });
                }}, icon("trash", 16), "Server löschen")));
    }

    renderJar();
    renderStart();
    return () => {
        disposed = true;
    };
}
