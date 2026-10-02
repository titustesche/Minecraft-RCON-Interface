// Stroke icons (24px grid), same set as the console design
const PATHS = {
    console: 'M4 17l6-5-6-5M12 19h8',
    players: 'M16 19v-1a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v1M9 10a3 3 0 1 0 0-6 3 3 0 0 0 0 6M22 19v-1a4 4 0 0 0-3-3.9M16 4.1a3 3 0 0 1 0 5.8',
    backup: 'M21 8v12H3V8M1 4h22v4H1zM10 12h4',
    mods: 'M9 3v4M15 3v4M6 7h12v5a6 6 0 0 1-12 0zM12 18v3',
    settings: 'M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0M16 4v4M10 10v4M18 16v4',
    play: 'M7 4v16l13-8z',
    restart: 'M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7',
    stop: 'M6 6h12v12H6z',
    kill: 'M12 2v6M5.6 5.6a9 9 0 1 0 12.8 0',
    send: 'M5 12h14M13 6l6 6-6 6',
    search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14M20 20l-3.5-3.5',
    mention: 'M4 17l6-5-6-5M12 19h8',
    kick: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l-5-5 5-5M5 12h11',
    plus: 'M12 5v14M5 12h14',
    upload: 'M12 16V4M7 9l5-5 5 5M4 16v4h16v-4',
    download: 'M12 4v12M7 11l5 5 5-5M4 20h16',
    trash: 'M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3',
    cloud: 'M7 18a5 5 0 0 1-.9-9.9A6 6 0 0 1 17.6 9 4.5 4.5 0 0 1 17 18zM12 11v5M9.5 13.5L12 11l2.5 2.5',
    link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
    power: 'M12 3v9M6.3 6.3a8 8 0 1 0 11.4 0',
    check: 'M5 12l5 5 9-10',
    server: 'M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01',
    external: 'M14 4h6v6M20 4l-9 9M18 14v6H4V6h6',
    restore: 'M3 12a9 9 0 1 0 3-6.7M3 4v5h5M12 8v4l3 2',
};

export function icon(name, size = 18) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("width", size);
    svg.setAttribute("height", size);
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("fill", "none");
    svg.setAttribute("stroke", "currentColor");
    svg.setAttribute("stroke-width", "1.9");
    svg.setAttribute("stroke-linecap", "round");
    svg.setAttribute("stroke-linejoin", "round");
    svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", PATHS[name] ?? PATHS.server);
    svg.appendChild(path);
    return svg;
}
