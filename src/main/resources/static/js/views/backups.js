import {Api, enc} from "../api.js";
import {busy, clear, confirm, Format, h, icon, iconButton, Popup} from "../ui.js";

export function BackupsView({app, main}) {
    const id = app.current.config.id;
    const base = `/api/servers/${enc(id)}/backups`;
    const simplyfile = app.info.simplyfileEnabled;
    let poll = null;
    let disposed = false;
    let count, list, format, upload, createButton;

    clear(main,
        h("div", {class: "page-head"},
            h("div", {class: "page-head-title"},
                h("h1", {class: "title"}, "Backups"),
                h("p", {class: "muted small", ref: el => count = el}))),
        h("section", {class: "section"},
            h("h2", null, "Neues Backup"),
            h("p", {class: "muted small"}, "Packt den kompletten Serverordner (Welten, Mods, Konfiguration) in ein Archiv. Läuft der Server, wird vorher gespeichert und das automatische Speichern kurz pausiert."),
            h("div", {class: "button-row", style: "gap: 1rem"},
                h("label", {class: "field", style: "min-width: 10rem"},
                    h("span", null, "Format"),
                    h("select", {ref: el => format = el},
                        h("option", {value: "ZIP"}, ".zip"),
                        h("option", {value: "TAR_GZ"}, ".tar.gz"))),
                h("label", {class: "check", title: simplyfile ? app.info.simplyfileUrl : "simplycraft.simplyfile.url ist nicht gesetzt"},
                    h("input", {type: "checkbox", checked: simplyfile, disabled: !simplyfile, ref: el => upload = el}),
                    "Direkt in Simplyfile sichern"),
                createButton = h("button", {type: "button", class: "pill-button primary", style: "margin-left: auto", onClick: create},
                    icon("backup"), "Backup erstellen")),
            simplyfile ? null : h("p", {class: "muted small"}, "Simplyfile ist nicht verbunden. Setze SIMPLYCRAFT_SIMPLYFILE_URL, um Backups dort abzulegen.")),
        h("div", {class: "row-list", ref: el => list = el})
    );

    async function load() {
        try {
            const backups = await Api.get(base);
            if (disposed) return;
            render(backups);
            const busyNow = backups.some(b => b.inProgress || b.simplyfile?.status === "UPLOADING");
            clearTimeout(poll);
            if (busyNow) poll = setTimeout(load, 2000);
        } catch (e) {
            Popup.error("Backups konnten nicht geladen werden", e.message);
        }
    }

    function render(backups) {
        const total = backups.reduce((sum, b) => sum + (b.inProgress ? 0 : b.size), 0);
        count.textContent = `${backups.length} ${backups.length === 1 ? "Backup" : "Backups"} · ${Format.bytes(total)}`;
        createButton.disabled = backups.some(b => b.inProgress);
        clear(list, backups.length === 0 ? h("p", {class: "empty-text"}, "Noch keine Backups") : backups.map(row));
    }

    function row(backup) {
        const meta = backup.inProgress
            ? ["wird erstellt …", Format.bytes(backup.size)]
            : [Format.date(backup.createdAt), Format.bytes(backup.size)];
        return h("div", {class: "row"},
            h("div", {class: "avatar"}, backup.inProgress ? h("span", {class: "spinner"}) : icon("backup", 16)),
            h("div", {style: "min-width: 0"},
                h("p", {class: "row-title"}, backup.name),
                h("p", {class: "row-meta"}, meta.join(" · "), " ", simplyfileBadge(backup))),
            h("div", {class: "row-actions"}, backup.inProgress ? null : [
                h("a", {class: "icon-button", href: Api.url(`${base}/${enc(backup.name)}`), download: backup.name, title: "Herunterladen", "aria-label": "Herunterladen"}, icon("download", 16)),
                simplyfile ? iconButton("cloud", "In Simplyfile sichern", e => busy(e.currentTarget, async () => {
                    await Api.post(`${base}/${enc(backup.name)}/simplyfile`);
                    await load();
                }), {disabled: backup.simplyfile?.status === "UPLOADING"}) : null,
                iconButton("trash", "Backup löschen", async e => {
                    if (!await confirm("Backup löschen", `${backup.name} wird endgültig gelöscht. Eine Kopie in Simplyfile bleibt erhalten.`, "Löschen")) return;
                    await busy(e.currentTarget, async () => {
                        await Api.del(`${base}/${enc(backup.name)}`);
                        await load();
                    });
                }, {class: "danger"}),
            ]));
    }

    function simplyfileBadge(backup) {
        const upload = backup.simplyfile;
        if (!upload) return null;
        if (upload.status === "UPLOADING") return h("span", {class: "badge accent-text"}, "lädt zu Simplyfile …");
        if (upload.status === "FAILED") return h("span", {class: "badge danger-text", title: upload.error ?? ""}, "Simplyfile-Upload fehlgeschlagen");
        return h("a", {class: "badge tertiary-text", style: "color: var(--tertiary-accent)", href: upload.url, target: "_blank", rel: "noopener"}, icon("check", 12), "in Simplyfile");
    }

    async function create() {
        await busy(createButton, async () => {
            await Api.post(base, {format: format.value, uploadToSimplyfile: upload.checked});
            Popup.info("Backup gestartet", "Der Fortschritt erscheint auch in der Konsole");
            await load();
        });
    }

    load();
    return () => {
        disposed = true;
        clearTimeout(poll);
    };
}
