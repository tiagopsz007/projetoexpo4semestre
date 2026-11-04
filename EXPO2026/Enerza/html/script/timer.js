/* ===== Enerzā - timers (cozinha e banheiro) =====
   <div class="chips" data-timer="banho" data-msg="O tempo do seu banho acabou!" data-presets="5,10,15"></div>
   Ao zerar: toca um aviso sonoro e a tela pisca em vermelho com a frase, até clicar em "Parar alarme".
   O timer guarda a hora de término no navegador, então continua certo se você trocar de página. */
(function () {
    const ctx0 = { audio: null, alarme: null, tituloOriginal: document.title };

    /* ---------- som (Web Audio, sem arquivo) ---------- */
    function liberarAudio() {              // precisa ser chamado dentro de um clique (regra dos navegadores)
        try {
            const AC = window.AudioContext || window.webkitAudioContext;
            if (!AC) return null;
            if (!ctx0.audio) ctx0.audio = new AC();
            if (ctx0.audio.state === "suspended") ctx0.audio.resume();
        } catch (_) { }
        return ctx0.audio;
    }

    function apitar() {
        const ac = liberarAudio();
        if (!ac) return;
        const t0 = ac.currentTime + 0.05;
        [0, 0.35, 0.7].forEach(off => {    // três bipes seguidos
            const o = ac.createOscillator(), g = ac.createGain();
            o.type = "square"; o.frequency.value = 880;
            g.gain.setValueAtTime(0.0001, t0 + off);
            g.gain.exponentialRampToValueAtTime(0.3, t0 + off + 0.02);
            g.gain.exponentialRampToValueAtTime(0.0001, t0 + off + 0.25);
            o.connect(g); g.connect(ac.destination);
            o.start(t0 + off); o.stop(t0 + off + 0.3);
        });
    }

    /* ---------- alarme na tela ---------- */
    function mostrarAlarme(msg) {
        if (ctx0.alarme) return;
        const d = document.createElement("div");
        d.className = "alarme"; d.setAttribute("role", "alertdialog"); d.setAttribute("aria-live", "assertive");
        const ic = document.createElement("div"); ic.className = "alarme-ic"; ic.textContent = "⏰";
        const t = document.createElement("div"); t.className = "alarme-msg"; t.textContent = msg;
        const b = document.createElement("button"); b.className = "btn"; b.type = "button"; b.textContent = "Parar alarme";
        b.onclick = fecharAlarme;
        d.append(ic, t, b);
        document.body.append(d);
        ctx0.alarme = d;
        document.title = "⏰ " + msg;
        b.focus();
    }

    function fecharAlarme() {
        if (ctx0.alarme) ctx0.alarme.remove();
        ctx0.alarme = null;
        document.title = ctx0.tituloOriginal;
    }
    document.addEventListener("keydown", e => { if (e.key === "Escape") fecharAlarme(); });

    /* ---------- timer ---------- */
    function iniciarTimer(el) {
        const chave = "enerza.timer." + el.dataset.timer;
        const msg = el.dataset.msg || "O tempo acabou!";
        const presets = (el.dataset.presets || "5,10,15").split(",").map(Number).filter(n => n > 0);
        let st = lerEstado();   // {estado:'parado'|'rodando'|'pausado', fim, restante}

        function lerEstado() {
            try { return JSON.parse(localStorage.getItem(chave)) || { estado: "parado" }; } catch (_) { return { estado: "parado" }; }
        }
        function salvar() { try { localStorage.setItem(chave, JSON.stringify(st)); } catch (_) { } }

        // ---- interface ----
        const display = document.createElement("div"); display.className = "big";
        const campos = document.createElement("div"); campos.className = "tm-campos";
        const min = document.createElement("input"), seg = document.createElement("input");
        for (const [i, ph, max] of [[min, "min", 180], [seg, "seg", 59]]) {
            i.type = "number"; i.min = 0; i.max = max; i.placeholder = ph; i.setAttribute("aria-label", ph === "min" ? "minutos" : "segundos");
        }
        campos.append(min, ":", seg);
        const pre = document.createElement("div"); pre.className = "tm-pre";
        presets.forEach(m => {
            const a = document.createElement("a"); a.href = "#"; a.className = "btn o peq"; a.textContent = m + " min";
            a.onclick = e => { e.preventDefault(); if (st.estado === "parado") { min.value = m; seg.value = ""; render(); } };
            pre.append(a);
        });
        const ini = document.createElement("a"); ini.href = "#"; ini.className = "btn"; ini.textContent = "Iniciar";
        const zer = document.createElement("a"); zer.href = "#"; zer.className = "btn o"; zer.textContent = "Zerar";
        el.replaceChildren(display, campos, pre, ini, zer);

        const fmt = s => {
            const m = Math.floor(s / 60), r = s % 60;
            return String(m).padStart(2, "0") + ":" + String(r).padStart(2, "0");
        };
        const restante = () => st.estado === "rodando" ? Math.max(0, Math.ceil((st.fim - Date.now()) / 1000))
            : st.estado === "pausado" ? st.restante : 0;

        function render() {
            const parado = st.estado === "parado";
            display.textContent = parado ? fmt(Math.max(0, (+min.value || 0) * 60 + (+seg.value || 0))) : fmt(restante());
            ini.textContent = st.estado === "rodando" ? "Pausar" : (st.estado === "pausado" ? "Continuar" : "Iniciar");
            min.disabled = seg.disabled = !parado;
            pre.classList.toggle("off", !parado);
            el.classList.toggle("rodando", st.estado === "rodando");
        }

        function terminar() {
            st = { estado: "parado" }; salvar(); render();
            apitar();
            mostrarAlarme(msg);
        }

        function tic() {
            if (st.estado === "rodando") {
                if (Date.now() >= st.fim) terminar(); else render();
            }
        }

        ini.onclick = e => {
            e.preventDefault();
            liberarAudio();                               // aproveita o clique para liberar o som
            if (st.estado === "rodando") {
                st = { estado: "pausado", restante: restante() };
            } else if (st.estado === "pausado") {
                st = { estado: "rodando", fim: Date.now() + st.restante * 1000 };
            } else {
                const total = (+min.value || 0) * 60 + (+seg.value || 0);
                if (total <= 0) { min.focus(); el.classList.add("erro"); setTimeout(() => el.classList.remove("erro"), 600); return; }
                st = { estado: "rodando", fim: Date.now() + total * 1000 };
            }
            salvar(); render();
        };
        zer.onclick = e => { e.preventDefault(); st = { estado: "parado" }; salvar(); render(); };
        [min, seg].forEach(i => {
            i.addEventListener("input", render);
            i.addEventListener("keydown", e => { if (e.key === "Enter") ini.click(); });
        });

        // se o tempo acabou enquanto a página estava fechada, avisa agora
        if (st.estado === "rodando" && Date.now() >= st.fim) terminar(); else render();
        setInterval(tic, 250);
    }

    document.querySelectorAll("[data-timer]").forEach(iniciarTimer);
})();
