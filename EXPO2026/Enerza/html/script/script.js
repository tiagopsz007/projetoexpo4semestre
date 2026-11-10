/* ===== Enerzā - página de início =====
   O navegador fala só com o servidor Java (/api/...). O token do Spotify fica no Java. */
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

/* ---------- Sessão ---------- */
$("sair").onclick = async e => {
    e.preventDefault();
    try { await fetch("/api/auth/logout", { method: "POST" }); } catch (_) { }
    location.href = "/";
};
