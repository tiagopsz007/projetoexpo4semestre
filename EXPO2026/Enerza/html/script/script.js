/* ===== Enerzā - página de início =====
   O navegador fala só com o servidor Java (/api/...). Tokens do Spotify e da Xiaomi ficam no Java. */
const $ = id => document.getElementById(id);

/* ---------- Spotify ---------- */
let spConectado = false, tocando = false;

function spMsg(titulo, artista) {
    $("sp-titulo").textContent = titulo; $("sp-artista").textContent = artista;
    $("sp-cover").style.background = ""; $("sp-prog").style.width = "0%";
}

async function spAtualizar() {
    try {
        const r = await fetch("/api/spotify/player");
        const txt = await r.text();
        let s = {};
        try { s = JSON.parse(txt); } catch (_) { }

        if (r.status === 403) return spMsg("Spotify recusou (403)", txt.slice(0, 80) || "Confira o app no painel do Spotify");
        spConectado = s.conectado !== false;
        if (!spConectado) return spMsg("Spotify desconectado", "Clique aqui para conectar");
        if (!r.ok) return spMsg("Erro do Spotify (" + r.status + ")", txt.slice(0, 80));
        const t = s.item;
        if (!t) return spMsg("Nada tocando", "Abra o Spotify e toque algo");
        tocando = s.is_playing;
        $("sp-titulo").textContent = t.name;
        $("sp-artista").textContent = (t.artists || t.show && [t.show] || []).map(a => a.name).join(", ");
        const imgs = (t.album || t.show || {}).images || [];
        $("sp-cover").style.background = imgs[0] ? `url(${imgs[0].url}) center/cover` : "";
        $("sp-prog").style.width = (s.progress_ms / t.duration_ms * 100) + "%";
        $("sp-pp").textContent = tocando ? "⏸" : "▶";
    } catch (e) {
        console.error("Spotify:", e);
        spMsg("Servidor offline", "Inicie o executar.bat");
    }
}


async function spComando(acao, espera) {
    if (!spConectado) return location.href = "/spotify/login";
    const r = await fetch("/api/spotify/" + acao, { method: "POST" });
    const txt = await r.text();
    if (r.status === 403) spMsg("Comando recusado", "Controle exige Spotify Premium");
    else if (r.status === 404) spMsg("Nenhum aparelho ativo", "Abra o Spotify em algum aparelho");
    setTimeout(spAtualizar, espera);
}

$("sp-info").onclick = () => { if (!spConectado) location.href = "/spotify/login"; };
$("sp-pp").onclick = () => spComando(tocando ? "pause" : "play", 300);
$("sp-next").onclick = () => spComando("next", 500);
$("sp-prev").onclick = () => spComando("previous", 500);

if (new URLSearchParams(location.search).get("spotify") === "erro") {
    history.replaceState({}, "", location.pathname);
    spMsg("Falha no login", "Confira o config.properties");
} else spAtualizar();
setInterval(spAtualizar, 3000);

/* ---------- Xiaomi ---------- */
function xmLinha(d) {
    const linha = document.createElement("div");
    linha.className = "row";
    const ic = document.createElement("div"); ic.className = "ic"; ic.textContent = d.icone;
    const txt = document.createElement("div");
    const b = document.createElement("b"); b.textContent = d.nome;
    const sm = document.createElement("small");
    sm.textContent = d.erro ? "⚠ " + d.erro : (d.ligado ? "Ligado" : "Desligado");
    txt.append(b, sm);

    const tg = document.createElement("label"); tg.className = "tg";
    const cb = document.createElement("input"); cb.type = "checkbox"; cb.checked = !!d.ligado; cb.disabled = !!d.erro;
    tg.append(cb, document.createElement("span"));
    cb.onchange = async () => {
        cb.disabled = true; sm.textContent = "Enviando...";
        try {
            const r = await fetch(`/api/xiaomi/ligar?id=${encodeURIComponent(d.id)}&valor=${cb.checked}`, { method: "POST" });
            const j = await r.json();
            if (!r.ok) throw new Error(j.erro);
            sm.textContent = cb.checked ? "Ligado" : "Desligado";
        } catch (e) {
            cb.checked = !cb.checked; sm.textContent = "⚠ " + e.message;
        }
        cb.disabled = false;
    };
    linha.append(ic, txt, tg);
    return linha;
}

async function xmCarregar() {
    const lista = $("xm-lista");
    try {
        const r = await fetch("/api/xiaomi/dispositivos");
        const ds = await r.json();
        lista.replaceChildren(...ds.map(xmLinha));
        if (!ds.length) lista.textContent = "Nenhum aparelho configurado no config.properties.";
    } catch (e) {
        lista.textContent = "Servidor offline.";
    }
}
xmCarregar();
