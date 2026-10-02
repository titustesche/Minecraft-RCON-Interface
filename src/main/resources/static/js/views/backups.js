import {Api, enc} from "../api.js";
import {busy, clear, confirm, dropzone, Format, h, icon, iconButton, isRunning, modal, Popup} from "../ui.js";

export function BackupsView({app, main}) {
    const id = app.current.config.id;
    const base = `/api/servers/${enc(id)}/backups`;
    const simplyfile = app.info.simplyfileEnabled;
    let poll = null;
    let restorePoll = null;
    let disposed = false;
    let restoring = null;
    let count, list, format, upload, createButton, restoreBanner, importProgress;

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
        h("div", {class: "section hidden", role: "status", ref: el => restoreBanner = el}),
        h("div", {class: "row-list", ref: el => list = el}),
        dropzone({
            title: "Backup hochladen",
            hint: ".zip oder .tar.gz, z. B. aus Simplyfile heruntergeladen · erscheint danach in der Liste",
            accept: ".zip,.tar.gz,.tgz",
            multiple: false,
            onFiles: ([file]) => importArchive(file),
        }),
        h("div", {class: "progress-line hidden", ref: el => importProgress = el}, h("div"))
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
                iconButton("restore", "Backup laden", () => restore(backup), {disabled: restoring != null}),
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

    // --- restore -----------------------------------------------------------------------

    async function restore(backup) {
        if (isRunning(app.status?.state)) {
            return Popup.warn("Server läuft", "Stoppe den Server, bevor du ein Backup lädst.");
        }
        let safety;
        const ok = await modal("Backup laden", h("div", {style: "display: flex; flex-direction: column; gap: 0.75rem"},
            h("p", {style: "margin: 0"}, `Der Serverordner wird komplett durch ${backup.name} ersetzt (Welten, Mods, Einstellungen).`),
            h("label", {class: "check"},
                h("input", {type: "checkbox", checked: true, ref: el => safety = el}),
                "Aktuellen Stand vorher als Backup sichern")), [
            {label: "Abbrechen"},
            {label: "Backup laden", primary: true, value: () => ({safetyBackup: safety.checked})},
        ]);
        if (!ok) return;
        try {
            restoring = await Api.post(`${base}/${enc(backup.name)}/restore`, ok);
            renderRestore();
            await load();
            watchRestore();
        } catch (e) {
            Popup.error("Laden nicht möglich", e.message);
        }
    }

    function renderRestore() {
        restoreBanner.classList.toggle("hidden", !restoring);
        if (!restoring) return;
        clear(restoreBanner, h("div", {class: "button-row"},
            h("span", {class: "spinner"}),
            h("span", null, `Backup ${restoring.backup} wird geladen … der Server ist solange gesperrt.`)));
    }

    function watchRestore() {
        clearTimeout(restorePoll);
        restorePoll = setTimeout(async () => {
            let status = null;
            try {
                status = await Api.get(`/api/servers/${enc(id)}/restore`);
            } catch { /* try again */ }
            if (disposed) return;
            if (status?.status === "RUNNING" || !status) return watchRestore();
            restoring = null;
            renderRestore();
            if (status.status === "DONE") {
                Popup.info("Backup geladen", status.backup + (status.safetyBackup ? ` · vorheriger Stand: ${status.safetyBackup}` : ""), 6);
                // Settings, jar and mods may have changed with the restored directory
                app.setCurrent(await Api.get(`/api/servers/${enc(id)}`));
            } else {
                Popup.error("Wiederherstellung fehlgeschlagen", status.error ?? "Unbekannter Fehler");
            }
            await load();
        }, 1500);
    }

    async function importArchive(file) {
        importProgress.classList.remove("hidden");
        const form = new FormData();
        form.append("file", file);
        try {
            const backup = await Api.upload(`${base}/upload`, form, p => importProgress.firstElementChild.style.width = `${Math.round(p * 100)}%`);
            Popup.info("Backup hochgeladen", backup.name);
            await load();
        } catch (e) {
            Popup.error("Upload fehlgeschlagen", e.message);
        } finally {
            importProgress.classList.add("hidden");
            importProgress.firstElementChild.style.width = "0";
        }
    }

    async function create() {
        await busy(createButton, async () => {
            await Api.post(base, {format: format.value, uploadToSimplyfile: upload.checked});
            Popup.info("Backup gestartet", "Der Fortschritt erscheint auch in der Konsole");
            await load();
        });
    }

    load();
    // A restore started earlier (or in another tab) is still running
    Api.get(`/api/servers/${enc(id)}/restore`).then(status => {
        if (disposed || status?.status !== "RUNNING") return;
        restoring = status;
        renderRestore();
        watchRestore();
    }).catch(() => {});
    return () => {
        disposed = true;
        clearTimeout(poll);
        clearTimeout(restorePoll);
    };
}
