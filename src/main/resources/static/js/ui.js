import {icon} from "./icons.js";

/** Small DOM builder: h("div", {class: "x", onClick}, "text", child). Text is always set as text. */
export function h(tag, props, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props ?? {})) {
        if (value == null || value === false) continue;
        if (key === "class") el.className = value;
        else if (key === "style") el.style.cssText = value;
        else if (key === "ref") value(el);
        else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2).toLowerCase(), value);
        else if (key === "value" || key === "checked" || key === "selected") el[key] = value;
        else el.setAttribute(key, value === true ? "" : value);
    }
    append(el, children);
    return el;
}

function append(el, children) {
    for (const child of children.flat(Infinity)) {
        if (child == null || child === false) continue;
        el.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    }
}

export function clear(el, ...children) {
    el.replaceChildren();
    append(el, children);
    return el;
}

export function iconButton(name, label, onClick, extra = {}) {
    return h("button", {type: "button", class: "icon-button " + (extra.class ?? ""), "aria-label": label, title: label, onClick, disabled: extra.disabled}, icon(name, 16));
}

export function actionButton(name, label, onClick, extra = {}) {
    return h("button", {type: "button", class: "action-button " + (extra.class ?? ""), onClick, disabled: extra.disabled}, icon(name, 16), label);
}

/** Runs an async action, disables the button meanwhile and shows errors as popup */
export async function busy(button, action) {
    if (button) button.disabled = true;
    try {
        return await action();
    } catch (e) {
        Popup.error("Fehler", e.message ?? String(e));
    } finally {
        if (button) button.disabled = false;
    }
}

// --- Popups (same look as Simplyfile) --------------------------------------------------

export const Popup = {
    info: (title, message, timeout = 4) => popup("#80ff80", title, message, timeout),
    warn: (title, message, timeout = 6) => popup("#ffff80", title, message, timeout),
    error: (title, message, timeout = 7) => popup("#ff8080", title, message, timeout),
};

function popup(color, title, message, timeout) {
    const container = document.getElementById("popup-container");
    const el = h("div", {class: "popup", role: "status", style: `--popup-color: ${color}`},
        h("h1", {class: "popup-title"}, title),
        h("p", {class: "popup-body"}, message ?? ""),
        h("div", {class: "popup-progress-bar", style: `--timeout: ${timeout}s`}));
    container.appendChild(el);
    setTimeout(() => el.classList.add("destroyed"), (timeout - 0.5) * 1000);
    setTimeout(() => el.remove(), timeout * 1000);
}

// --- Modals ------------------------------------------------------------------------------

export function modal(title, content, buttons) {
    return new Promise(resolve => {
        const background = h("div", {class: "modal-background"});
        const close = (result) => {
            background.remove();
            document.removeEventListener("keydown", onKey);
            resolve(result);
        };
        const onKey = (e) => { if (e.key === "Escape") close(undefined); };
        const container = h("div", {class: "modal-container", role: "dialog", "aria-modal": "true", "aria-label": title},
            h("h1", {class: "title"}, title),
            content,
            h("div", {class: "button-row"}, buttons.map(b =>
                h("button", {type: b.submit ? "submit" : "button", class: "pill-button " + (b.primary ? "primary" : ""), onClick: () => close(b.value?.())}, b.label)))
        );
        background.addEventListener("click", (e) => { if (e.target === background) close(undefined); });
        document.addEventListener("keydown", onKey);
        background.appendChild(container);
        document.body.appendChild(background);
        container.querySelector("input, button.primary")?.focus();
    });
}

export async function confirm(title, message, confirmLabel = "OK") {
    const result = await modal(title, h("p", {style: "margin: 0"}, message), [
        {label: "Abbrechen"},
        {label: confirmLabel, primary: true, value: () => true},
    ]);
    return result === true;
}

export async function prompt(title, label, {value = "", type = "text", placeholder = ""} = {}) {
    let input;
    const content = h("label", {class: "field"}, h("span", null, label),
        h("input", {type, value, placeholder, ref: el => input = el, autocomplete: "off"}));
    input.addEventListener("keydown", e => {
        if (e.key === "Enter") e.target.closest(".modal-container").querySelector("button.primary").click();
    });
    return modal(title, content, [
        {label: "Abbrechen"},
        {label: "OK", primary: true, value: () => input.value},
    ]);
}

// --- Formatting --------------------------------------------------------------------------

export const Format = {
    bytes(n) {
        if (!Number.isFinite(n)) return "–";
        if (n < 1024) return n + " B";
        if (n < 1048576) return (n / 1024).toFixed(1).replace(".", ",") + " KB";
        if (n < 1073741824) return (n / 1048576).toFixed(1).replace(".", ",") + " MB";
        return (n / 1073741824).toFixed(2).replace(".", ",") + " GB";
    },
    duration(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0) return "–";
        const m = Math.floor(seconds / 60), hours = Math.floor(m / 60);
        if (hours > 0) return `${hours} h ${m % 60} min`;
        if (m > 0) return `${m} min`;
        return `${seconds} s`;
    },
    date(value) {
        if (!value) return "–";
        return new Date(value).toLocaleString("de-DE", {dateStyle: "medium", timeStyle: "short"});
    },
    count(n) {
        return new Intl.NumberFormat("de-DE", {notation: n >= 10000 ? "compact" : "standard"}).format(n);
    },
    /** "4G" -> bytes */
    memory(value) {
        const m = /^(\d+)([KMG])?$/i.exec(value ?? "");
        if (!m) return NaN;
        return Number(m[1]) * ({K: 1024, M: 1048576, G: 1073741824}[(m[2] ?? "").toUpperCase()] ?? 1);
    },
};

export const STATE_LABEL = {
    OFFLINE: "Offline",
    STARTING: "Startet",
    ONLINE: "Online",
    STOPPING: "Stoppt",
    CRASHED: "Abgestürzt",
};

export function stateClass(state) {
    return (state ?? "OFFLINE").toLowerCase();
}

export function isRunning(state) {
    return state === "STARTING" || state === "ONLINE" || state === "STOPPING";
}

/** Drag & drop + click file picker, like the Simplyfile upload area */
export function dropzone({title, hint, accept, multiple, onFiles}) {
    let input;
    const zone = h("label", {class: "dropzone"},
        h("input", {type: "file", accept, multiple, hidden: true, ref: el => input = el}),
        icon("upload", 36),
        h("span", {class: "dropzone-title"}, title),
        h("span", {class: "muted small"}, hint));
    input.addEventListener("change", () => {
        if (input.files.length) onFiles([...input.files]);
        input.value = "";
    });
    zone.addEventListener("dragover", e => { e.preventDefault(); zone.classList.add("dragging"); });
    zone.addEventListener("dragleave", () => zone.classList.remove("dragging"));
    zone.addEventListener("drop", e => {
        e.preventDefault();
        zone.classList.remove("dragging");
        if (e.dataTransfer.files.length) onFiles([...e.dataTransfer.files]);
    });
    return zone;
}

export {icon};
