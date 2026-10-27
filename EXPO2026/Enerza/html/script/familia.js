const $ = id => document.getElementById(id);

function mostrarMsg(texto, ok) {
    const m = $("msg");
    m.textContent = texto;
    m.className = "fam-msg " + (ok ? "ok" : "erro");
}

function cartao(m, souAdmin) {
    const box = document.createElement("div"); box.className = "box fam" + (m.eu ? " eu" : "");
    const av = document.createElement("div"); av.className = "av"; av.textContent = "👤";
    const info = document.createElement("div"); info.className = "fam-info";
    const nome = document.createElement("div"); nome.className = "fam-nome"; nome.textContent = m.nome;
    if (m.eu) { const v = document.createElement("em"); v.textContent = "(você)"; nome.append(v); }
    const email = document.createElement("div"); email.className = "fam-email"; email.textContent = m.email;
    info.append(nome, email);
    const cargo = document.createElement("span");
    const admin = m.cargo === "admin";
    cargo.className = "badge" + (admin ? " admin" : "");
    cargo.textContent = admin ? "Administrador" : "Usuário comum";
    box.append(av, info, cargo);
    if (souAdmin && !m.eu) {
        const admin2 = m.cargo === "admin";
        const car = document.createElement("a");
        car.className = "btn o peq"; car.href = "#"; car.textContent = admin2 ? "Tornar usuário comum" : "Promover a admin";
        car.onclick = e => { e.preventDefault(); mudarCargo(m, admin2 ? "usuario" : "admin"); };
        box.append(car);
        const rem = document.createElement("a");
        rem.className = "btn perigo"; rem.href = "#"; rem.textContent = "Remover";
        rem.onclick = e => { e.preventDefault(); remover(m); };
        box.append(rem);
    }
    return box;
}

async function carregar() {
    try {
        const r = await fetch("/api/auth/familia");
        if (r.status === 401) { location.href = "login2.html"; return; }
        const j = await r.json();
        $("titulo").textContent = j.familia || "Família";
        $("add").style.display = j.souAdmin ? "flex" : "none";
        $("lista").replaceChildren(...j.membros.map(m => cartao(m, j.souAdmin)));
    } catch (e) {
        $("lista").textContent = "Servidor offline.";
    }
}

async function adicionar() {
    const email = $("emailNovo").value.trim();
    if (!email) { mostrarMsg("Informe o email.", false); return; }
    try {
        const r = await fetch("/api/auth/familia/adicionar", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({ email })
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.erro || "Falha ao adicionar.");
        mostrarMsg(j.nome + " entrou na sua família.", true);
        $("emailNovo").value = "";
        carregar();
    } catch (e) {
        mostrarMsg(e instanceof TypeError ? "Servidor offline." : e.message, false);
    }
}

async function mudarCargo(m, cargo) {
    const txt = cargo === "admin" ? "Promover " + m.nome + " a administrador?" : "Tornar " + m.nome + " usuário comum?";
    if (!confirm(txt)) return;
    try {
        const r = await fetch("/api/auth/familia/cargo", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({ email: m.email, cargo })
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.erro || "Falha ao alterar o cargo.");
        mostrarMsg(j.nome + (cargo === "admin" ? " agora é administrador(a)." : " agora é usuário comum."), true);
        carregar();
    } catch (e) {
        mostrarMsg(e instanceof TypeError ? "Servidor offline." : e.message, false);
    }
}

async function remover(m) {
    if (!confirm("Remover " + m.nome + " da família?\nEla volta para a família que criou no cadastro e será administradora dela.")) return;
    try {
        const r = await fetch("/api/auth/familia/remover", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({ email: m.email })
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.erro || "Falha ao remover.");
        mostrarMsg(j.nome + " foi removido(a) da família.", true);
        carregar();
    } catch (e) {
        mostrarMsg(e instanceof TypeError ? "Servidor offline." : e.message, false);
    }
}

$("adicionar").onclick = e => { e.preventDefault(); adicionar(); };
$("emailNovo").addEventListener("keydown", e => { if (e.key === "Enter") adicionar(); });
$("sair").onclick = async e => {
    e.preventDefault();
    try { await fetch("/api/auth/logout", { method: "POST" }); } catch (_) { }
    location.href = "/";
};
carregar();
