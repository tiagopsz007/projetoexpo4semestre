/* ===== Enerzā - gastos de energia dos aparelhos cadastrados em "Dispositivos" =====
   - páginas de cômodo:  <div class="gastos" data-comodo="sala">   -> aparelhos daquele cômodo
   - página de início:   <div class="gastos" data-top="5">         -> os que mais gastam na casa
   Tudo dentro de uma função para não conflitar com o "const $" do script.js. */
(function () {
    const el = document.querySelector(".gastos");
    if (!el) return;
    const comodo = el.dataset.comodo || "";
    const top = parseInt(el.dataset.top || "0", 10);
    const NOMES = { sala: "Sala", cozinha: "Cozinha", quarto: "Quarto", banheiro: "Banheiro" };
    let intervalo = 10;

    const fmt = w => (Math.round(w * 10) / 10).toString().replace(".", ",") + " W";
    const medido = d => d.watts !== null && d.watts !== undefined;
    // ligado: usa a medição da tomada (se tiver) ou a potência cadastrada; desligado: 0
    const consumo = d => d.ligado !== true ? 0 : (medido(d) ? d.watts : d.potencia);

    function estado(d) {
        if (d.erro) return "⚠ " + d.erro;
        if (d.ligado !== true) return "Desligado";
        return medido(d) ? "Ligado · medido" : "Ligado · estimado";
    }

    function linha(icone, nome, sub, watts, extra) {
        const r = document.createElement("div"); r.className = "row" + (extra ? " " + extra : "");
        const ic = document.createElement("div"); ic.className = "ic"; ic.textContent = icone;
        const t = document.createElement("div");
        const b = document.createElement("b"); b.textContent = nome;
        const sm = document.createElement("small"); sm.textContent = sub;
        t.append(b, sm);
        const w = document.createElement("span"); w.className = "w"; w.textContent = watts;
        r.append(ic, t, w);
        return r;
    }

    function vazio(texto, comLink) {
        const sm = document.createElement("small"); sm.className = "gastos-vazio"; sm.textContent = texto;
        if (comLink) {
            const a = document.createElement("a"); a.href = "dispositivos.html"; a.textContent = "Dispositivos";
            sm.append(" Cadastre em ", a, ".");
        }
        el.replaceChildren(sm);
    }

    async function carregar() {
        try {
            const r = await fetch("/api/dispositivos/lista");
            if (r.status === 401) { location.href = "login2.html"; return; }
            const j = await r.json();
            intervalo = j.intervalo || 10;
            if (!j.configurado) return vazio("Tuya não configurada no config.properties.");

            const todos = j.dispositivos;
            let ds = comodo ? todos.filter(d => d.comodo === comodo) : todos.slice();
            if (!ds.length) return vazio(comodo ? "Nenhum aparelho cadastrado em " + NOMES[comodo].toLowerCase() + "." : "Nenhum aparelho cadastrado.", true);

            ds.sort((a, b) => consumo(b) - consumo(a) || a.nome.localeCompare(b.nome));
            if (top > 0) ds = ds.slice(0, top);

            const linhas = ds.map(d => linha(d.icone, d.nome, (comodo ? "" : (NOMES[d.comodo] || "") + " · ") + estado(d), consumo(d) ? fmt(consumo(d)) : "0 W"));
            const total = (comodo ? ds : todos).reduce((s, d) => s + consumo(d), 0);
            linhas.push(linha("⚡", comodo ? "Total do cômodo" : "Total da casa", "agora", fmt(total), "total"));
            el.replaceChildren(...linhas);
        } catch (e) {
            vazio("Servidor offline.");
        }
    }

    (async function ciclo(primeira) {
        if (primeira || document.visibilityState === "visible") await carregar();   // a 1ª carga sempre acontece
        setTimeout(ciclo, intervalo * 1000);
    })(true);
    document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") carregar(); });
})();
