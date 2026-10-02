const TOKEN_KEY = "simplycraft-token";

/**
 * REST client. The backend URL defaults to the page origin; another frontend of the
 * ecosystem can set window.SIMPLYCRAFT_API_URL before loading the app.
 */
export const Api = {
    base: (window.SIMPLYCRAFT_API_URL ?? window.location.origin).replace(/\/$/, ""),
    token: readToken(),
    /** Called on HTTP 401, should resolve once a new token was entered (or false) */
    onUnauthorized: async () => false,

    setToken(token) {
        Api.token = token || null;
        try {
            if (token) localStorage.setItem(TOKEN_KEY, token);
            else localStorage.removeItem(TOKEN_KEY);
        } catch { /* storage blocked */ }
    },

    headers(extra = {}) {
        return Api.token ? {...extra, Authorization: `Bearer ${Api.token}`} : extra;
    },

    async request(method, path, body, retried = false) {
        const isForm = body instanceof FormData;
        const response = await fetch(Api.base + path, {
            method,
            headers: Api.headers(body && !isForm ? {"Content-Type": "application/json"} : {}),
            body: body == null ? undefined : (isForm ? body : JSON.stringify(body)),
        }).catch(() => { throw new Error("Backend nicht erreichbar"); });

        if (response.status === 401 && !retried && await Api.onUnauthorized()) {
            return Api.request(method, path, body, true);
        }
        if (!response.ok) throw new Error(await errorMessage(response));
        if (response.status === 204 || response.status === 202 && response.headers.get("content-length") === "0") return null;
        const text = await response.text();
        return text ? JSON.parse(text) : null;
    },

    get: (path) => Api.request("GET", path),
    post: (path, body) => Api.request("POST", path, body ?? null),
    patch: (path, body) => Api.request("PATCH", path, body),
    del: (path) => Api.request("DELETE", path),

    /** Multipart upload with progress (fetch cannot report upload progress) */
    upload(path, formData, onProgress) {
        return new Promise((resolve, reject) => {
            const request = new XMLHttpRequest();
            request.open("POST", Api.base + path);
            for (const [key, value] of Object.entries(Api.headers())) request.setRequestHeader(key, value);
            request.upload.addEventListener("progress", e => {
                if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total);
            });
            request.addEventListener("load", () => {
                let data = null;
                try { data = request.responseText ? JSON.parse(request.responseText) : null; } catch { /* not json */ }
                if (request.status >= 200 && request.status < 300) resolve(data);
                else reject(new Error(data?.error ?? `HTTP ${request.status}`));
            });
            request.addEventListener("error", () => reject(new Error("Netzwerkfehler")));
            request.send(formData);
        });
    },

    /** URL for EventSource and download links, which cannot send headers */
    url(path) {
        const url = new URL(Api.base + path);
        if (Api.token) url.searchParams.set("token", Api.token);
        return url.href;
    },

    async reachable() {
        try {
            return (await fetch(Api.base + "/status")).ok;
        } catch {
            return false;
        }
    },
};

function readToken() {
    try {
        return localStorage.getItem(TOKEN_KEY);
    } catch {
        return null;
    }
}

async function errorMessage(response) {
    try {
        const data = await response.json();
        if (data?.error) return data.error;
    } catch { /* not json */ }
    return `HTTP ${response.status}`;
}

export const enc = encodeURIComponent;
