/* SuperSmart front-end: keys, scan start/poll, evidence filters, section nav */
const KEYS = ["serpapi", "google_key", "google_cx", "brave", "numverify"];
const getKeys = () => { try { return JSON.parse(localStorage.getItem("ss_keys") || "{}"); } catch (e) { return {}; } };
const setKeys = (k) => { try { localStorage.setItem("ss_keys", JSON.stringify(k)); } catch (e) {} };

// ---- settings dialog
(function () {
  const dlg = document.getElementById("settings");
  if (!dlg) return;
  const inputs = dlg.querySelectorAll("[data-key]");
  const load = () => { const k = getKeys(); inputs.forEach(i => i.value = k[i.dataset.key] || ""); };
  document.getElementById("open-settings").onclick = () => { load(); dlg.showModal(); };
  dlg.querySelector("form").addEventListener("submit", () => {
    const k = {}; inputs.forEach(i => { if (i.value.trim()) k[i.dataset.key] = i.value.trim(); }); setKeys(k);
  });
  document.getElementById("clear-keys").onclick = () => { setKeys({}); load(); };
  document.getElementById("test-prov").onclick = async () => {
    const k = {}; inputs.forEach(i => { if (i.value.trim()) k[i.dataset.key] = i.value.trim(); });
    const out = document.getElementById("prov-test"); out.textContent = "Testing…";
    try {
      const r = await fetch("/api/providers", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({keys: k})});
      const d = await r.json();
      out.innerHTML = d.test ? Object.entries(d.test).map(([n, v]) => `${v.ok ? "🟢" : "🔴"} ${n}: ${v.ok ? "OK" : v.note} (${v.seconds}s)`).join("<br>") : d.error;
    } catch (e) { out.textContent = "Test failed."; }
  };
})();

// ---- live type detection under the search bar
(function () {
  const q = document.querySelector(".searchbar input[name=q]"), out = document.getElementById("detected");
  if (!q || !out) return;
  let t; q.addEventListener("input", () => {
    clearTimeout(t); t = setTimeout(async () => {
      if (!q.value.trim()) { out.textContent = ""; return; }
      try { const d = await (await fetch("/api/detect?q=" + encodeURIComponent(q.value))).json(); out.textContent = d.type ? "Detected: " + d.type.replace("_", " ") : ""; } catch (e) {}
    }, 300);
  });
})();

// ---- scan lifecycle
async function startScan(p) {
  const err = document.getElementById("err");
  try {
    const r = await fetch("/api/scan", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({...p, keys: getKeys()})});
    const d = await r.json();
    if (!r.ok) { err.textContent = d.error || "Could not start scan."; document.querySelector(".spinner").remove(); return; }
    history.replaceState(null, "", "/r/" + d.id);
    pollJob(d.id, false);
  } catch (e) { err.textContent = "Network error. Is the server running?"; }
}
async function pollJob(id, reloadOnDone) {
  let since = 0, lines = [];
  const el = document.getElementById("log"), err = document.getElementById("err");
  const tick = async () => {
    try {
      const d = await (await fetch(`/api/job/${id}?since=${since}`)).json();
      if (d.log && d.log.length) { lines = lines.concat(d.log); since = d.n; el.textContent = lines.map(l => `[${l.t}s] ${l.msg}`).join("\n"); el.scrollTop = el.scrollHeight; }
      if (d.status === "done") { location.href = "/r/" + id; return; }
      if (d.status === "error" || d.status === "missing") { err.textContent = d.error || "Scan failed."; const s = document.querySelector(".spinner"); if (s) s.remove(); return; }
    } catch (e) {}
    setTimeout(tick, 1200);
  };
  tick();
}

// ---- result page
(function () {
  const res = document.getElementById("results");
  if (res) {
    const t = document.getElementById("f-text"), s = document.getElementById("f-src"), l = document.getElementById("f-lvl"),
          m = document.getElementById("f-min"), mv = document.getElementById("f-min-v"), c = document.getElementById("f-count");
    const items = [...res.querySelectorAll(".res")];
    const apply = () => {
      const q = t.value.toLowerCase(); let n = 0; mv.textContent = m.value;
      items.forEach(e => {
        const ok = (!q || e.dataset.text.includes(q)) && (!s.value || e.dataset.src === s.value) &&
                   (!l.value || e.dataset.lvl === l.value) && (+e.dataset.score >= +m.value);
        e.hidden = !ok; if (ok) n++;
      });
      c.textContent = `Showing ${n} of ${items.length}`;
    };
    [t, s, l, m].forEach(e => e.addEventListener("input", apply)); apply();
  }
  const side = document.getElementById("side");
  if (side) {
    const links = [...side.querySelectorAll("a")], panels = links.map(a => document.querySelector(a.getAttribute("href")));
    const show = (id) => { panels.forEach(p => p && (p.hidden = p.id !== id)); links.forEach(a => a.classList.toggle("on", a.getAttribute("href") === "#" + id)); };
    const route = () => { const id = (location.hash || "#overview").slice(1); show(panels.some(p => p && p.id === id) ? id : "overview"); window.scrollTo({top: 0}); };
    window.addEventListener("hashchange", route); route();
  }
  const cp = document.getElementById("copy-link");
  if (cp) cp.onclick = () => { navigator.clipboard.writeText(location.origin + location.pathname); cp.textContent = "Copied ✓"; };
})();
