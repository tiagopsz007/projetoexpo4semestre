const $ = id => document.getElementById(id);
        let enviando = false;

        async function cadastrar() {
            if (enviando) return;
            const msg = $("msg");
            msg.textContent = "";

            const nome = $("nome").value.trim(), email = $("email").value.trim();
            const senha = $("senha").value, confirmar = $("confirmar").value;
            if (!nome || !email || !senha) { msg.textContent = "Preencha todos os campos."; return; }
            if (senha.length < 6) { msg.textContent = "A senha deve ter pelo menos 6 caracteres."; return; }
            if (senha !== confirmar) { msg.textContent = "As senhas não conferem."; return; }

            enviando = true;
            $("cadastrar").textContent = "Cadastrando...";
            try {
                const r = await fetch("/api/auth/cadastro", {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body: new URLSearchParams({ nome, email, senha, confirmar })
                });
                const j = await r.json().catch(() => ({}));
                if (!r.ok) throw new Error(j.erro || "Falha no cadastro.");
                location.href = "inicio.html";   // o cadastro já deixa o usuário logado
            } catch (e) {
                msg.textContent = e instanceof TypeError
                    ? "Servidor offline. Inicie o executar.bat e acesse por http://127.0.0.1:8080"
                    : e.message;
            }
            enviando = false;
            $("cadastrar").textContent = "Cadastrar";
        }

        $("cadastrar").onclick = e => { e.preventDefault(); cadastrar(); };
        document.querySelectorAll("input").forEach(i =>
            i.addEventListener("keydown", e => { if (e.key === "Enter") cadastrar(); }));