const $ = id => document.getElementById(id);
let enviando = false;

async function entrar() {
    if (enviando) return;
    const msg = $("msg");
    msg.textContent = "";
    if (!$("email").value.trim() || !$("senha").value) {
        msg.textContent = "Preencha email e senha.";
        return;
    }
    enviando = true;
    $("entrar").textContent = "Entrando...";
    try {
        const r = await fetch("/api/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: new URLSearchParams({
                email: $("email").value,
                senha: $("senha").value,
                manter: $("manter").checked
            })
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.erro || "Falha no login.");
        location.href = "inicio.html";
    } catch (e) {
        msg.textContent = e instanceof TypeError
            ? "Servidor offline. Inicie o executar.bat e acesse por http://127.0.0.1:8080"
            : e.message;
    }
    enviando = false;
    $("entrar").textContent = "Entrar";
}

$("entrar").onclick = e => { e.preventDefault(); entrar(); };
document.querySelectorAll("input").forEach(i =>
    i.addEventListener("keydown", e => { if (e.key === "Enter") entrar(); }));