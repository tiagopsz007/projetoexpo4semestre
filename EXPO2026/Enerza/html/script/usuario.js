const $ = id => document.getElementById(id);
let enviando = false;

function mostrarMsg(texto, ok) {
    const m = $("msg");
    m.textContent = texto;
    m.className = "fam-msg " + (ok ? "ok" : "erro");
}

async function carregar() {
    try {
        const r = await fetch("/api/auth/eu");
        if (r.status === 401) { location.href = "login2.html"; return; }
        const j = await r.json();
        const admin = j.cargo === "admin";
        $("nome").textContent = j.nome;
        $("email").textContent = j.email;
        $("familia").textContent = j.familia;
        $("cargo").textContent = admin ? "Administrador" : "Usuário comum";
        $("cargo").className = "badge" + (admin ? " admin" : "");
    } catch (e) {
        $("nome").textContent = "Servidor offline";
    }
}

function fecharSenha() {
    $("senhaBox").style.display = "none";
    ["atual", "nova", "confirmar"].forEach(id => $(id).value = "");
    mostrarMsg("", true);
}

async function salvarSenha() {
    if (enviando) return;
    const atual = $("atual").value, nova = $("nova").value, confirmar = $("confirmar").value;
    if (!atual || !nova || !confirmar) { mostrarMsg("Preencha todos os campos.", false); return; }
    if (nova.length < 6) { mostrarMsg("A nova senha deve ter pelo menos 6 caracteres.", false); return; }
    if (nova !== confirmar) { mostrarMsg("As senhas não conferem.", false); return; }

    enviando = true;
    $("salvarSenha").textContent = "Salvando...";
    try {
        const r = await fetch("/api/auth/senha", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({ atual, nova, confirmar })
        });
        const j = await r.json().catch(() => ({}));
        if (r.status === 401) { location.href = "login2.html"; return; }
        if (!r.ok) throw new Error(j.erro || "Falha ao alterar a senha.");
        ["atual", "nova", "confirmar"].forEach(id => $(id).value = "");
        mostrarMsg("Senha alterada com sucesso.", true);
    } catch (e) {
        mostrarMsg(e instanceof TypeError ? "Servidor offline." : e.message, false);
    }
    enviando = false;
    $("salvarSenha").textContent = "Salvar";
}

$("abrirSenha").onclick = e => {
    e.preventDefault();
    const aberto = $("senhaBox").style.display === "flex";
    if (aberto) fecharSenha();
    else { $("senhaBox").style.display = "flex"; $("atual").focus(); }
};
$("cancelarSenha").onclick = e => { e.preventDefault(); fecharSenha(); };
$("salvarSenha").onclick = e => { e.preventDefault(); salvarSenha(); };
document.querySelectorAll("#senhaBox input").forEach(i =>
    i.addEventListener("keydown", e => { if (e.key === "Enter") salvarSenha(); }));
$("sair").onclick = async e => {
    e.preventDefault();
    try { await fetch("/api/auth/logout", { method: "POST" }); } catch (_) { }
    location.href = "/";
};
carregar();
