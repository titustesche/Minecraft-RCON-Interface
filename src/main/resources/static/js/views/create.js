import {Api} from "../api.js";
import {busy, clear, h, icon} from "../ui.js";

export function CreateView({app, main}) {
    let form, button;
    const field = (label, input) => h("label", {class: "field"}, h("span", null, label), input);

    clear(main,
        h("div", {class: "page-head"}, h("h1", {class: "title"}, "Neuer Server")),
        app.servers.length === 0 ? h("p", {class: "muted"}, "Willkommen! Lege deinen ersten Minecraft-Server an. Die Serverdatei (Version, Link oder eigene .jar) wählst du im nächsten Schritt.") : null,
        form = h("form", {class: "section", onSubmit: submit},
            h("div", {class: "form-grid"},
                field("Name", h("input", {name: "name", required: true, maxlength: 48, placeholder: "z. B. Survival", autocomplete: "off"})),
                field("Port", h("input", {name: "port", type: "number", min: 1, max: 65535, placeholder: "25565"})),
                field("Xms (Start-RAM)", h("input", {name: "xms", placeholder: app.info.defaultXms ?? "1G"})),
                field("Xmx (Max-RAM)", h("input", {name: "xmx", placeholder: app.info.defaultXmx ?? "4G"}))),
            h("p", {class: "muted small"}, "Der Server bekommt einen eigenen Ordner, z. B. servers/survival/ mit server.jar, mods/ und world/."),
            h("div", {class: "button-row"},
                button = h("button", {type: "submit", class: "pill-button primary"}, icon("plus"), "Server anlegen")))
    );
    form.querySelector("input").focus();

    async function submit(e) {
        e.preventDefault();
        const data = new FormData(form);
        const value = (key) => (data.get(key) ?? "").toString().trim() || null;
        await busy(button, async () => {
            const view = await Api.post("/api/servers", {
                name: value("name"),
                port: value("port") ? Number(value("port")) : null,
                xms: value("xms"),
                xmx: value("xmx"),
            });
            await app.loadServers();
            app.go(view.config.id, "settings");
        });
    }
}
