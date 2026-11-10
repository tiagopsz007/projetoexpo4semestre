const $ = id => document.getElementById(id);
let souAdmin = false, ocupado = false, intervalo = 10, luzId = null;

function mostrarMsg(texto, ok) {
    const m = $("msg");
    m.textContent = texto;
    m.className = "fam-msg " + (ok ? "ok" : "erro");
}

async function api(url, corpo) {
    const opc = corpo ? { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(corpo) } : {};
    const r = await fetch(url, opc);
    if (r.status === 401) { location.href = "login2.html"; throw new Error("sessão expirada"); }
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.erro || "Falha na requisição.");
    return j;
}

const NOMES = { sala: "Sala", cozinha: "Cozinha", quarto: "Quarto", banheiro: "Banheiro" };
const fmtW = w => String(w).replace(".", ",") + " W";

function textoPequeno(d) {
    const sala = (NOMES[d.comodo] || "") + " · ";
    if (d.erro) return sala + "⚠ " + d.erro;
    if (d.ligado && d.watts !== null) return sala + "consumo: " + fmtW(d.watts);
    return sala + (d.potencia ? "total: " + d.potencia + "w" : (d.ligado ? "ligado" : "desligado"));
}

function cartao(d) {
    const lb = document.createElement("label");
    lb.className = "dc" + (d.erro ? " indisp" : "");
    const cb = document.createElement("input");
    cb.type = "checkbox"; cb.checked = d.ligado === true; cb.disabled = !!d.erro;
    const e = document.createElement("span"); e.className = "e"; e.textContent = d.icone;
    const b = document.createElement("b"); b.textContent = d.nome;
    const sm = document.createElement("small"); sm.textContent = textoPequeno(d);
    lb.append(cb, e, b, sm);

    cb.onchange = async () => {
        const novo = cb.checked;
        cb.disabled = true; ocupado = true; sm.textContent = "Enviando...";
        try {
            await api("/api/dispositivos/ligar", { id: d.id, valor: novo });
            setTimeout(carregar, 1500);          // dá tempo do aparelho atualizar e mostra o consumo
        } catch (err) {
            cb.checked = !novo; sm.textContent = "⚠ " + err.message;
        }
        cb.disabled = false; ocupado = false;
    };

    if (d.luz && !d.erro) {
        const bt = document.createElement("button");
        bt.className = "lz"; bt.type = "button"; bt.textContent = "🎨 Cor e brilho";
        bt.onclick = ev => { ev.preventDefault(); ev.stopPropagation(); abrirLuz(d); };
        lb.append(bt);
    }

    if (souAdmin) {
        const cm = document.createElement("select"); cm.className = "cm"; cm.title = "Cômodo";
        for (const k in NOMES) cm.append(new Option(NOMES[k], k));
        cm.value = d.comodo;
        cm.onclick = ev => ev.stopPropagation();
        cm.onchange = async () => {
            try { await api("/api/dispositivos/editar", { id: d.id, comodo: cm.value }); carregar(); }
            catch (err) { mostrarMsg(err.message, false); cm.value = d.comodo; }
        };
        lb.append(cm);
        const x = document.createElement("button");
        x.className = "x"; x.type = "button"; x.title = "Remover aparelho"; x.textContent = "✕";
        x.onclick = async ev => {
            ev.preventDefault(); ev.stopPropagation();
            if (!confirm("Remover " + d.nome + " da família?")) return;
            try { await api("/api/dispositivos/remover", { id: d.id }); mostrarMsg(d.nome + " removido.", true); carregar(); }
            catch (err) { mostrarMsg(err.message, false); }
        };
        lb.append(x);
    }
    return lb;
}

async function carregar() {
    if (ocupado) return;
    if (document.activeElement && document.activeElement.classList.contains("cm")) return;   // não fecha o seletor aberto
    try {
        const j = await api("/api/dispositivos/lista");
        souAdmin = j.souAdmin;
        intervalo = j.intervalo || 10;
        $("abrirAdd").style.display = souAdmin ? "inline-block" : "none";
        $("descobertos").dataset.pode = j.podeDescobrir ? "1" : "";
        const ds = j.dispositivos;
        $("lista").replaceChildren(...ds.map(cartao));
        if (luzId !== null) {
            const l = ds.find(x => x.id === luzId);
            if (l && l.luz) sincLuz(l, false); else fecharLuz();
        }
        if (!j.configurado) $("resumo").textContent = "Tuya não configurada: preencha tuya.client.id e tuya.client.secret no config.properties.";
        else if (!ds.length) $("resumo").textContent = souAdmin ? "Nenhum aparelho ainda. Clique em “+ Adicionar aparelho”." : "Nenhum aparelho cadastrado na sua família.";
        else {
            const lig = ds.filter(d => d.ligado === true);
            const medido = lig.filter(d => d.watts !== null).reduce((s, d) => s + d.watts, 0);
            const temMedicao = lig.some(d => d.watts !== null);
            $("resumo").textContent = lig.length + " de " + ds.length + " ligado(s)" + (temMedicao ? " · consumo medido: " + fmtW(medido.toFixed(1)) : "");
        }
    } catch (e) {
        if (e instanceof TypeError) $("resumo").textContent = "Servidor offline. Inicie o executar.bat";
        else if (e.message !== "sessão expirada") $("resumo").textContent = e.message;
    }
}

/* ---------- lâmpada: cor, brilho e branco ---------- */
const CORES = ["#ff0000", "#ff8000", "#ffff00", "#00ff00", "#00ffff", "#0000ff", "#8000ff", "#ff00ff"];
let luzMexendo = false;

function montarCores() {
    const caixa = $("luzSw");
    CORES.forEach(c => {
        const b = document.createElement("button");
        b.type = "button"; b.className = "sw"; b.style.background = c; b.title = c;
        b.onclick = () => { $("luzCor").value = c; enviarLuz({ cor: c }); };
        caixa.append(b);
    });
    const br = document.createElement("button");
    br.type = "button"; br.className = "sw branco"; br.textContent = "☀"; br.title = "Luz branca";
    br.onclick = () => enviarLuz({ modo: "branco" });
    caixa.append(br);
}

function abrirLuz(d) {
    luzId = d.id;
    $("luzBox").style.display = "flex";
    sincLuz(d, true);
    $("luzBox").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function fecharLuz() { luzId = null; $("luzBox").style.display = "none"; }

function sincLuz(d, forcar) {
    const z = d.luz;
    $("luzNome").textContent = d.icone + " " + d.nome;
    $("luzCorLin").style.display = z.temCor ? "flex" : "none";
    $("luzBrilhoLin").style.display = z.temBrilho ? "flex" : "none";
    $("luzTempLin").style.display = z.temTemp ? "flex" : "none";
    const lig = d.ligado === true;
    $("luzBox").classList.toggle("desl", !lig);
    $("luzAviso").style.display = lig ? "none" : "block";
    if (!forcar && (luzMexendo || $("luzBox").contains(document.activeElement))) return;   // não atropela quem está mexendo
    $("luzCor").value = z.cor;
    $("luzBrilho").value = z.brilho; $("luzBrilhoV").textContent = z.brilho + "%";
    if (z.temp !== null) $("luzTemp").value = z.temp;
}

async function enviarLuz(params) {
    if (luzId === null) return;
    luzMexendo = true;
    try {
        await api("/api/dispositivos/luz", { id: luzId, ...params });
        mostrarMsg("", true);
        setTimeout(() => { luzMexendo = false; carregar(); }, 1200);
    } catch (e) {
        luzMexendo = false;
        mostrarMsg(e.message, false);
    }
}

$("luzCor").onchange = e => enviarLuz({ cor: e.target.value });
$("luzBrilho").oninput = e => { $("luzBrilhoV").textContent = e.target.value + "%"; };
$("luzBrilho").onchange = e => enviarLuz({ brilho: e.target.value });
$("luzTemp").onchange = e => enviarLuz({ temp: e.target.value });
$("luzFechar").onclick = e => { e.preventDefault(); fecharLuz(); };
montarCores();

/* ---------- adicionar ---------- */
async function abrirAdd() {
    $("addBox").style.display = "flex";
    mostrarMsg("", true);
    const sel = $("descobertos");
    if (!sel.dataset.pode) { sel.style.display = "none"; $("tuyaId").focus(); return; }
    try {
        const j = await api("/api/dispositivos/descobrir");
        sel.replaceChildren(new Option("Escolher da conta Smart Life...", ""),
            ...j.aparelhos.map(a => new Option(a.nome + (a.online ? "" : " (offline)"), a.id)));
        sel.style.display = j.aparelhos.length ? "block" : "none";
    } catch (e) { mostrarMsg(e.message, false); }
}

function fecharAdd() {
    $("addBox").style.display = "none";
    ["tuyaId", "nome", "potencia", "comodo"].forEach(id => $(id).value = "");
    $("descobertos").value = "";
}

async function salvarAdd() {
    try {
        await api("/api/dispositivos/adicionar", {
            tuyaId: $("tuyaId").value.trim(), nome: $("nome").value.trim(),
            icone: $("icone").value, comodo: $("comodo").value, potencia: $("potencia").value
        });
        mostrarMsg("Aparelho adicionado.", true);
        fecharAdd(); carregar();
    } catch (e) { mostrarMsg(e.message, false); }
}

$("descobertos").onchange = e => {
    const o = e.target.selectedOptions[0];
    if (!o || !o.value) return;
    $("tuyaId").value = o.value;
    if (!$("nome").value) $("nome").value = o.text.replace(" (offline)", "");
};
$("abrirAdd").onclick = e => { e.preventDefault(); abrirAdd(); };
$("cancelarAdd").onclick = e => { e.preventDefault(); fecharAdd(); };
$("salvarAdd").onclick = e => { e.preventDefault(); salvarAdd(); };
document.querySelectorAll("#addBox input").forEach(i =>
    i.addEventListener("keydown", e => { if (e.key === "Enter") salvarAdd(); }));

$("sair").onclick = async e => {
    e.preventDefault();
    try { await fetch("/api/auth/logout", { method: "POST" }); } catch (_) { }
    location.href = "/";
};

// atualiza sozinha no intervalo definido em tuya.atualizar.segundos, só com a aba visível (a Tuya limita as chamadas)
(async function ciclo(primeira) {
    if (primeira || document.visibilityState === "visible") await carregar();   // a 1ª carga sempre acontece
    setTimeout(ciclo, intervalo * 1000);
})(true);
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") carregar(); });
