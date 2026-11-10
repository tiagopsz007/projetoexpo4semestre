/* ===== Enerzā - painel de aparelhos (tomadas e lâmpada) =====
   <div class="box ctl-painel" data-comodo="banheiro"></div>  -> só os aparelhos desse cômodo
   <div class="box ctl-painel"></div>                          -> todos os aparelhos da casa
   Liga/desliga tomadas e lâmpadas, escolhe a cor da lâmpada na roda de cores e ajusta o brilho.
   Tudo dentro de uma função para não conflitar com o "const $" de outros scripts. */
(function () {
    const raiz = document.querySelector(".ctl-painel");
    if (!raiz) return;
    const comodo = raiz.dataset.comodo || "";
    const comLigarTudo = raiz.hasAttribute("data-ligar-tudo");     // botão "Ligar tudo" só onde o HTML pedir
    const NOMES = { sala: "Sala", cozinha: "Cozinha", quarto: "Quarto", banheiro: "Banheiro" };
    const ICONE_POWER = '<svg viewBox="0 0 100 100" aria-hidden="true"><path d="M50 8 V48" fill="none" stroke="currentColor" stroke-width="9" stroke-linecap="round"/>'
        + '<path d="M28 24 A38 38 0 1 0 72 24" fill="none" stroke="currentColor" stroke-width="9" stroke-linecap="round"/></svg>';

    let intervalo = 10, lista = [], assinatura = "", lampSel = null;
    const ocupados = new Set();           // ids com comando em andamento (não atualizar enquanto isso)
    let refs = { plugs: {}, lamp: null };
    let el = {};

    /* ---------- utilidades ---------- */
    async function api(url, corpo) {
        const opc = corpo ? { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(corpo) } : {};
        const r = await fetch(url, opc);
        if (r.status === 401) { location.href = "login2.html"; throw new Error("sessão expirada"); }
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.erro || "Falha na requisição.");
        return j;
    }

    const fmtW = w => (Math.round(w * 10) / 10).toString().replace(".", ",") + " W";
    const consumo = d => d.ligado !== true ? 0 : (d.watts !== null && d.watts !== undefined ? d.watts : d.potencia);

    function textoPlug(d) {
        if (d.erro) return "⚠ " + d.erro;
        const estimado = d.ligado === true && (d.watts === null || d.watts === undefined) && d.potencia;
        return "consumo: " + (estimado ? "~" : "") + fmtW(consumo(d));
    }

    function msg(texto) { if (el.msg) el.msg.textContent = texto || ""; }

    function hexParaHsv(hex) {
        const r = parseInt(hex.slice(1, 3), 16) / 255, g = parseInt(hex.slice(3, 5), 16) / 255, b = parseInt(hex.slice(5, 7), 16) / 255;
        const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
        let h = 0;
        if (d) h = max === r ? 60 * (((g - b) / d) % 6) : max === g ? 60 * ((b - r) / d + 2) : 60 * ((r - g) / d + 4);
        return [(h + 360) % 360, max ? d / max : 0, max];
    }

    function hsvParaHex(h, s, v) {
        const c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        const [r, g, b] = [[c, x, 0], [x, c, 0], [0, c, x], [0, x, c], [x, 0, c], [c, 0, x]][Math.floor(h / 60) % 6];
        return "#" + [r, g, b].map(n => Math.round((n + m) * 255).toString(16).padStart(2, "0")).join("");
    }

    /* ---------- montagem ---------- */
    function esqueleto() {
        const topo = document.createElement("div"); topo.className = "ctl-topo";
        const t = document.createElement("b"); t.textContent = comodo ? "Aparelhos · " + NOMES[comodo] : "Aparelhos da casa";
        const acoes = document.createElement("div"); acoes.className = "ctl-acoes";
        let lig = null;
        if (comLigarTudo) {
            lig = document.createElement("a"); lig.href = "#"; lig.className = "btn o peq"; lig.textContent = "Ligar tudo";
            lig.onclick = e => { e.preventDefault(); alterarTudo(true); };
            acoes.append(lig);
        }
        const todos = document.createElement("a"); todos.href = "#"; todos.className = "btn o peq"; todos.textContent = "Desligar tudo";
        todos.onclick = e => { e.preventDefault(); alterarTudo(false); };
        acoes.append(todos);
        topo.append(t, acoes);
        const m = document.createElement("div"); m.className = "ctl-msg";
        const corpo = document.createElement("div"); corpo.className = "ctl-corpo";
        raiz.replaceChildren(topo, m, corpo);
        el = { msg: m, corpo, acoes, todos, lig };
    }

    function vazio(texto, comLink) {
        const p = document.createElement("div"); p.className = "ctl-vazio"; p.textContent = texto;
        if (comLink) {
            const a = document.createElement("a"); a.href = "dispositivos.html"; a.textContent = "Dispositivos";
            p.append(" Cadastre em ", a, ".");
        }
        el.corpo.replaceChildren(p);
        el.corpo.className = "ctl-corpo";
        el.acoes.style.display = "none";
        assinatura = ""; refs = { plugs: {}, lamp: null };
    }

    function cartaoPlug(d) {
        const box = document.createElement("div"); box.className = "ctl-plug";
        const nome = document.createElement("div"); nome.className = "ctl-nome"; nome.textContent = d.nome;
        const lin = document.createElement("div"); lin.className = "ctl-lin";
        const info = document.createElement("small");
        const tg = document.createElement("label"); tg.className = "tg";
        const cb = document.createElement("input"); cb.type = "checkbox";
        tg.append(cb, document.createElement("span"));
        lin.append(info, tg);
        box.append(nome, lin);
        cb.onchange = async () => {
            const novo = cb.checked;
            ocupados.add(d.id); cb.disabled = true; info.textContent = "enviando...";
            try { await api("/api/dispositivos/ligar", { id: d.id, valor: novo }); msg(""); }
            catch (e) { cb.checked = !novo; msg(e.message); }
            cb.disabled = false;
            setTimeout(() => { ocupados.delete(d.id); carregar(); }, 1200);
        };
        refs.plugs[d.id] = { box, cb, info };
        return box;
    }

    function cartaoLampada(lamps, d) {
        const z = d.luz;
        const box = document.createElement("div"); box.className = "ctl-lamp";
        const nome = document.createElement("div"); nome.className = "ctl-nome"; nome.textContent = d.nome;
        box.append(nome);

        if (lamps.length > 1) {                       // várias lâmpadas: escolher qual controlar
            const abas = document.createElement("div"); abas.className = "ctl-abas";
            lamps.forEach(l => {
                const b = document.createElement("button"); b.type = "button"; b.textContent = l.nome;
                b.className = l.id === d.id ? "on" : "";
                b.onclick = () => { lampSel = l.id; desenhar(); };
                abas.append(b);
            });
            box.append(abas);
        }

        const power = document.createElement("button");
        power.type = "button"; power.className = "ctl-power"; power.title = "Ligar / desligar"; power.innerHTML = ICONE_POWER;
        power.onclick = async () => {
            const novo = !(refs.lamp && refs.lamp.ligado);
            ocupados.add(d.id); power.disabled = true;
            try { await api("/api/dispositivos/ligar", { id: d.id, valor: novo }); msg(""); }
            catch (e) { msg(e.message); }
            power.disabled = false;
            setTimeout(() => { ocupados.delete(d.id); carregar(); }, 1200);
        };
        box.append(power);

        let roda = null, marca = null, brilho = null;
        if (z.temCor) {
            roda = document.createElement("div"); roda.className = "ctl-roda"; roda.title = "Escolha a cor";
            marca = document.createElement("i"); marca.className = "ctl-marca";
            roda.append(marca);
            roda.onclick = async e => {
                const r = roda.getBoundingClientRect(), raio = r.width / 2;
                const dx = e.clientX - (r.left + raio), dy = e.clientY - (r.top + raio), dist = Math.hypot(dx, dy);
                if (!raio || dist > raio) return;
                const h = (Math.atan2(dy, dx) * 180 / Math.PI + 90 + 360) % 360, s = Math.min(1, dist / raio);
                const cor = hsvParaHex(h, s, 1);
                ocupados.add(d.id); posicionar(marca, h, s);
                try { await api("/api/dispositivos/luz", { id: d.id, cor }); msg(""); }
                catch (err) { msg(err.message); }
                setTimeout(() => { ocupados.delete(d.id); carregar(); }, 1200);
            };
            box.append(roda);
        }
        if (z.temBrilho) {
            brilho = document.createElement("input"); brilho.type = "range"; brilho.min = 1; brilho.max = 100; brilho.className = "ctl-brilho";
            brilho.title = "Brilho";
            brilho.onchange = async () => {
                ocupados.add(d.id);
                try { await api("/api/dispositivos/luz", { id: d.id, brilho: brilho.value }); msg(""); }
                catch (err) { msg(err.message); }
                setTimeout(() => { ocupados.delete(d.id); carregar(); }, 1200);
            };
            box.append(brilho);
        }
        refs.lamp = { id: d.id, box, power, roda, marca, brilho, ligado: false };
        return box;
    }

    function posicionar(marca, h, s) {
        const ang = (h - 90) * Math.PI / 180;
        marca.style.left = (50 + Math.cos(ang) * s * 50) + "%";
        marca.style.top = (50 + Math.sin(ang) * s * 50) + "%";
    }

    function construir(plugs, lamps) {
        refs = { plugs: {}, lamp: null };
        const filhos = [];
        if (plugs.length) {
            const col = document.createElement("div"); col.className = "ctl-plugs";
            col.append(...plugs.map(cartaoPlug));
            filhos.push(col);
        }
        if (lamps.length) filhos.push(cartaoLampada(lamps, lamps.find(l => l.id === lampSel) || lamps[0]));
        el.corpo.className = "ctl-corpo" + (plugs.length && lamps.length ? "" : " uma");
        el.corpo.replaceChildren(...filhos);
    }

    /* ---------- atualização sem redesenhar ---------- */
    function atualizar(plugs, lamps) {
        plugs.forEach(d => {
            const r = refs.plugs[d.id];
            if (!r || ocupados.has(d.id)) return;
            r.cb.checked = d.ligado === true;
            r.cb.disabled = !!d.erro;
            r.box.classList.toggle("indisp", !!d.erro);
            r.info.textContent = textoPlug(d);
        });
        const l = refs.lamp && lamps.find(x => x.id === refs.lamp.id);
        if (l && !ocupados.has(l.id)) {
            const z = l.luz;
            refs.lamp.ligado = l.ligado === true;
            refs.lamp.power.classList.toggle("on", refs.lamp.ligado);
            refs.lamp.box.classList.toggle("desl", !refs.lamp.ligado);
            if (refs.lamp.marca) {
                const [h, s] = hexParaHsv(z.cor);
                refs.lamp.marca.style.display = z.modo === "colour" ? "block" : "none";
                posicionar(refs.lamp.marca, h, s);
            }
            if (refs.lamp.brilho && document.activeElement !== refs.lamp.brilho) refs.lamp.brilho.value = z.brilho;
        }
        el.acoes.style.display = "";
        const todos = plugs.concat(lamps);
        el.todos.classList.toggle("off", !todos.some(d => d.ligado === true));
        if (el.lig) el.lig.classList.toggle("off", !todos.some(d => d.ligado === false && !d.erro));
    }

    function desenhar() {
        const ds = comodo ? lista.filter(d => d.comodo === comodo) : lista;
        const plugs = ds.filter(d => !d.luz), lamps = ds.filter(d => d.luz);
        if (!ds.length) return vazio(comodo ? "Nenhum aparelho cadastrado em " + NOMES[comodo].toLowerCase() + "." : "Nenhum aparelho cadastrado.", true);
        if (lamps.length && !lamps.some(l => l.id === lampSel)) lampSel = lamps[0].id;
        const sig = JSON.stringify([plugs.map(p => [p.id, p.nome]), lamps.map(l => [l.id, l.nome, l.luz.temCor, l.luz.temBrilho]), lampSel]);
        if (sig !== assinatura) { construir(plugs, lamps); assinatura = sig; }
        atualizar(plugs, lamps);
    }

    /** Liga (valor=true) ou desliga (valor=false) todos os aparelhos do escopo que estão no estado oposto. */
    async function alterarTudo(valor) {
        const alvo = lista.filter(d => (!comodo || d.comodo === comodo) && !d.erro && d.ligado === !valor);
        if (!alvo.length) return;
        if (!comodo && !confirm(valor ? "Ligar todos os aparelhos da casa?" : "Desligar todos os aparelhos da casa?")) return;
        msg(valor ? "Ligando..." : "Desligando...");
        const falhas = [];
        await Promise.all(alvo.map(d => api("/api/dispositivos/ligar", { id: d.id, valor }).catch(() => falhas.push(d.nome))));
        msg(falhas.length ? "Não consegui " + (valor ? "ligar" : "desligar") + ": " + falhas.join(", ") : "");
        setTimeout(carregar, 1200);
    }

    async function carregar() {
        try {
            const j = await api("/api/dispositivos/lista");
            intervalo = j.intervalo || 10;
            if (!el.corpo) esqueleto();
            if (!j.configurado) return vazio("Tuya não configurada no config.properties.");
            lista = j.dispositivos;
            desenhar();
        } catch (e) {
            if (!el.corpo) esqueleto();
            if (e.message !== "sessão expirada") vazio(e instanceof TypeError ? "Servidor offline." : e.message);
        }
    }

    (async function ciclo(primeira) {
        if (primeira || document.visibilityState === "visible") await carregar();
        setTimeout(ciclo, intervalo * 1000);
    })(true);
    document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") carregar(); });
})();
