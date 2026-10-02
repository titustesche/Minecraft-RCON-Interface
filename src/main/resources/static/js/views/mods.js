import {Api, enc} from "../api.js";
import {actionButton, busy, clear, confirm, dropzone, Format, h, icon, iconButton, isRunning, Popup} from "../ui.js";

export function ModsView({app, main}) {
    const id = app.current.config.id;
    const base = `/api/servers/${enc(id)}`;
    const config = app.current.config;
    const hasLoader = config.loader && config.loader !== "vanilla";
    let mods = [];
    let installed = new Set();
    let disposed = false;

    let count, list, uploadProgress, results, searchInput, resultInfo, moreButton;
    let query = "", offset = 0;

    clear(main,
        h("div", {class: "page-head"},
            h("div", {class: "page-head-title"},
                h("h1", {class: "title"}, "Mods"),
                h("p", {class: "muted small", ref: el => count = el})),
            h("p", {class: "muted small", style: "margin: 0"},
                hasLoader ? `Loader ${config.loader}${config.gameVersion ? " · Minecraft " + config.gameVersion : ""}` : "Kein Mod-Loader")),
        restartHint(),
        dropzone({
            title: "Mods hierher ziehen",
            hint: "oder klicken · .jar-Dateien landen im mods-Ordner des Servers",
            accept: ".jar",
            multiple: true,
            onFiles: upload,
        }),
        h("div", {class: "progress-line hidden", ref: el => uploadProgress = el}, h("div")),
        h("div", {class: "row-list", ref: el => list = el}),

        h("section", {class: "section", "aria-label": "Modrinth"},
            h("div", {class: "split", style: "align-items: center; flex-wrap: wrap"},
                h("h2", null, "Modrinth"),
                h("a", {class: "muted small", href: "https://modrinth.com/mods", target: "_blank", rel: "noopener"}, "modrinth.com")),
            hasLoader ? null : h("p", {class: "warn-text small"},
                "Dieser Server hat keinen Mod-Loader. Installiere unter Einstellungen eine Fabric-Version oder trage den Loader ein (z. B. bei Forge/NeoForge per Link)."),
            h("form", {class: "command-form", onSubmit: e => { e.preventDefault(); search(searchInput.value.trim(), 0); }},
                h("label", {class: "field-pill compact"},
                    icon("search", 16),
                    h("input", {type: "search", placeholder: "Mods auf Modrinth suchen, z. B. lithium", "aria-label": "Modrinth durchsuchen", ref: el => searchInput = el})),
                h("button", {type: "submit", class: "pill-button", "aria-label": "Suchen"}, icon("search"), h("span", {class: "label"}, "Suchen"))),
            h("p", {class: "muted small", ref: el => resultInfo = el}),
            h("div", {class: "row-list", ref: el => results = el}),
            moreButton = h("button", {type: "button", class: "pill-button hidden", onClick: e => busy(e.currentTarget, () => search(query, offset + 20, true))}, "Mehr laden"))
    );

    function restartHint() {
        return isRunning(app.status?.state)
            ? h("p", {class: "warn-text small", style: "margin: 0"}, "Änderungen an Mods werden erst nach einem Neustart des Servers aktiv.")
            : null;
    }

    async function load() {
        try {
            mods = await Api.get(`${base}/mods`);
            if (disposed) return;
            installed = new Set(mods.map(m => m.modrinth?.projectId).filter(Boolean));
            render();
        } catch (e) {
            Popup.error("Mods konnten nicht geladen werden", e.message);
        }
    }

    function render() {
        const active = mods.filter(m => m.enabled).length;
        count.textContent = `${mods.length} installiert · ${active} aktiv`;
        clear(list, mods.length === 0
            ? h("p", {class: "empty-text"}, "Noch keine Mods installiert")
            : mods.map(modRow));
        for (const row of results.querySelectorAll("[data-project]")) markInstalled(row);
    }

    function modRow(mod) {
        const source = mod.modrinth;
        const meta = [Format.bytes(mod.size)];
        if (source) meta.push(`Modrinth · ${source.versionNumber}`);
        else meta.push("hochgeladen");
        return h("div", {class: "row" + (mod.enabled ? "" : " disabled")},
            avatar(source?.iconUrl, source?.title ?? mod.name),
            h("div", {style: "min-width: 0"},
                h("p", {class: "row-title", title: mod.fileName}, source?.title ?? mod.name),
                h("p", {class: "row-meta"}, meta.join(" · "), mod.enabled ? "" : " · deaktiviert")),
            h("div", {class: "row-actions"},
                h("label", {class: "check small", title: mod.enabled ? "Deaktivieren" : "Aktivieren"},
                    h("input", {type: "checkbox", checked: mod.enabled, "aria-label": `${mod.name} aktiv`,
                        onChange: e => busy(e.currentTarget, async () => {
                            await Api.patch(`${base}/mods/${enc(mod.fileName)}`, {enabled: e.target.checked});
                            await load();
                        })})),
                source ? h("a", {class: "icon-button", href: `https://modrinth.com/mod/${enc(source.slug ?? source.projectId)}`, target: "_blank", rel: "noopener", title: "Auf Modrinth öffnen", "aria-label": "Auf Modrinth öffnen"}, icon("external", 16)) : null,
                iconButton("trash", "Mod löschen", async e => {
                    if (!await confirm("Mod löschen", `${mod.fileName} wird aus dem mods-Ordner gelöscht.`, "Löschen")) return;
                    await busy(e.currentTarget, async () => {
                        await Api.del(`${base}/mods/${enc(mod.fileName)}`);
                        await load();
                    });
                }, {class: "danger"})));
    }

    async function upload(files) {
        const jars = files.filter(f => f.name.toLowerCase().endsWith(".jar"));
        if (jars.length !== files.length) Popup.warn("Übersprungen", "Nur .jar-Dateien können als Mod hochgeladen werden");
        if (jars.length === 0) return;
        const form = new FormData();
        jars.forEach(f => form.append("files", f));
        uploadProgress.classList.remove("hidden");
        try {
            await Api.upload(`${base}/mods`, form, p => uploadProgress.firstElementChild.style.width = `${Math.round(p * 100)}%`);
            Popup.info("Hochgeladen", `${jars.length} ${jars.length === 1 ? "Mod" : "Mods"} hinzugefügt`);
            await load();
        } catch (e) {
            Popup.error("Upload fehlgeschlagen", e.message);
        } finally {
            uploadProgress.classList.add("hidden");
            uploadProgress.firstElementChild.style.width = "0";
        }
    }

    // --- Modrinth ----------------------------------------------------------------------------

    async function search(q, from, more = false) {
        query = q;
        offset = from;
        if (!more) clear(results, h("div", {class: "button-row muted small"}, h("span", {class: "spinner"}), "Suche läuft …"));
        try {
            const result = await Api.get(`${base}/modrinth/search?query=${enc(q)}&offset=${from}&limit=20`);
            if (disposed) return;
            if (!more) clear(results);
            if (result.hits.length === 0 && !more) results.appendChild(h("p", {class: "empty-text"}, "Keine passenden Mods gefunden"));
            result.hits.forEach(hit => results.appendChild(hitRow(hit)));
            const shown = from + result.hits.length;
            resultInfo.textContent = `${Format.count(result.total_hits)} Treffer` + (hasLoader ? ` für ${config.loader}${config.gameVersion ? " " + config.gameVersion : ""}` : "");
            moreButton.classList.toggle("hidden", shown >= result.total_hits);
        } catch (e) {
            clear(results, h("p", {class: "danger-text small"}, `Modrinth nicht erreichbar: ${e.message}`));
        }
    }

    function hitRow(hit) {
        let button;
        const row = h("div", {class: "row", "data-project": hit.project_id},
            avatar(hit.icon_url, hit.title, true),
            h("div", {style: "min-width: 0"},
                h("p", {class: "row-title"}, hit.title, h("span", {class: "muted small"}, ` von ${hit.author}`)),
                h("p", {class: "row-description"}, hit.description),
                h("p", {class: "row-meta"}, `${Format.count(hit.downloads)} Downloads · ${(hit.categories ?? []).slice(0, 4).join(", ")}`)),
            h("div", {class: "row-actions"},
                button = actionButton("download", "Installieren", () => install(hit, button), {class: "tertiary", disabled: !hasLoader})));
        markInstalled(row);
        return row;
    }

    function markInstalled(row) {
        const button = row.querySelector("button");
        if (!button || !installed.has(row.dataset.project)) return;
        button.replaceChildren(icon("check", 16), "Aktualisieren");
    }

    async function install(hit, button) {
        await busy(button, async () => {
            button.replaceChildren(h("span", {class: "spinner"}), "Installiere …");
            try {
                const result = await Api.post(`${base}/modrinth/install`, {projectId: hit.project_id});
                const names = result.installed.map(m => m.modrinth?.title ?? m.name);
                const deps = names.length > 1 ? ` (inkl. ${names.slice(1).join(", ")})` : "";
                Popup.info("Installiert", `${names[0] ?? hit.title}${deps}`);
                await load();
            } finally {
                button.replaceChildren(icon("download", 16), installed.has(hit.project_id) ? "Aktualisieren" : "Installieren");
                markInstalled(button.closest(".row"));
            }
        });
    }

    function avatar(url, name, large = false) {
        return h("div", {class: "avatar" + (large ? " large" : "")},
            url ? h("img", {src: url, alt: "", loading: "lazy", referrerpolicy: "no-referrer"}) : (name ?? "?").charAt(0).toUpperCase());
    }

    load();
    if (hasLoader) search("", 0);
    return () => { disposed = true; };
}
