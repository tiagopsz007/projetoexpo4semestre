/* ===== Enerzā - temperatura no topo da página =====
   Preenche <div id="temp-topo"> com a temperatura atual de Taboão da Serra - SP (Open-Meteo, sem chave de API). */
(function () {
    const el = document.getElementById("temp-topo");
    if (!el) return;
    // Coordenadas de Taboão da Serra - SP
    const url = "https://api.open-meteo.com/v1/forecast?latitude=-23.6261&longitude=-46.7533&current=temperature_2m";

    async function carregar() {
        try {
            const r = await fetch(url);
            const d = await r.json();
            el.textContent = Math.round(d.current.temperature_2m) + " c°";
        } catch (e) {
            console.log("Erro ao buscar o clima:", e);
            el.textContent = "-- c°";
        }
    }
    carregar();
    setInterval(carregar, 10 * 60 * 1000);      // atualiza a cada 10 minutos
})();
