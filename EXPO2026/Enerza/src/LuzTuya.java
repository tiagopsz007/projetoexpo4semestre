import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lâmpadas inteligentes Tuya (brilho, cor RGB, temperatura do branco).
 * Funciona com as duas versões de pontos de dados:
 *   v1: bright_value / temp_value / colour_data      v2: bright_value_v2 / temp_value_v2 / colour_data_v2
 * As faixas (min/max) vêm da própria lâmpada (endpoint /specifications); sem elas, usa valores padrão.
 * Só oferece o que a lâmpada realmente tem: ex.: uma lâmpada só de branco não mostra seletor de cor.
 */
class LuzTuya {

    private static final Pattern HEX = Pattern.compile("^#?[0-9a-fA-F]{6}$");

    final String codBrilho, codTemp, codCor;
    final boolean temModo;
    final double bMin, bMax, tMin, tMax, hMax, sMin, sMax, vMin, vMax;

    private LuzTuya(Map<String, Object> st, Map<String, Object> sp) {
        codBrilho = primeiro(st, "bright_value_v2", "bright_value");
        codTemp = primeiro(st, "temp_value_v2", "temp_value");
        codCor = primeiro(st, "colour_data_v2", "colour_data");
        temModo = st.containsKey("work_mode");
        boolean bV2 = codBrilho != null && codBrilho.endsWith("_v2");
        boolean tV2 = codTemp != null && codTemp.endsWith("_v2");
        boolean cV2 = codCor != null && codCor.endsWith("_v2");
        bMin = faixa(sp, codBrilho, "min", bV2 ? 10 : 25);
        bMax = faixa(sp, codBrilho, "max", bV2 ? 1000 : 255);
        tMin = faixa(sp, codTemp, "min", 0);
        tMax = faixa(sp, codTemp, "max", tV2 ? 1000 : 255);
        hMax = faixaCor(sp, codCor, "h", "max", 360);
        sMin = faixaCor(sp, codCor, "s", "min", 0);
        sMax = faixaCor(sp, codCor, "s", "max", cV2 ? 1000 : 255);
        vMin = faixaCor(sp, codCor, "v", "min", cV2 ? 10 : 0);
        vMax = faixaCor(sp, codCor, "v", "max", cV2 ? 1000 : 255);
    }

    /** Devolve null se o aparelho não tem brilho, cor nem temperatura (ex.: tomada). */
    static LuzTuya de(Map<String, Object> st, Map<String, Object> sp) {
        LuzTuya z = new LuzTuya(st, sp);
        return z.codBrilho == null && z.codTemp == null && z.codCor == null ? null : z;
    }

    /** JSON do estado para a página: modo, brilho (%), cor (#rrggbb), temperatura do branco (%) e o que a lâmpada suporta. */
    String estadoJson(Map<String, Object> st) {
        Map<?, ?> cor = codCor == null ? null : corAtual(st.get(codCor));
        double h = cor == null ? 0 : num(cor.get("h"), 0), s = cor == null ? 0 : num(cor.get("s"), 0);
        String hex = hex(h / hMax * 360, pct(s, sMin, sMax) / 100, 1);
        String temp = "null";
        if (codTemp != null && st.get(codTemp) instanceof Number)
            temp = String.valueOf(Math.round(pct(num(st.get(codTemp), tMin), tMin, tMax)));
        return "{\"modo\":\"" + Servidor.escaparJson(String.valueOf(st.get("work_mode"))) + "\",\"brilho\":" + Math.round(brilhoPct(st, cor))
                + ",\"cor\":\"" + hex + "\",\"temp\":" + temp + ",\"temCor\":" + (codCor != null)
                + ",\"temTemp\":" + (codTemp != null) + ",\"temBrilho\":" + (codBrilho != null || codCor != null) + "}";
    }

    /**
     * Monta os comandos Tuya a partir do que a página enviou: cor (#rrggbb), brilho (1-100), temp (0-100) ou modo=branco.
     * @param corComoTexto colour_data pode ser aceito como texto JSON ou como objeto, conforme a lâmpada; o chamador tenta os dois.
     * @throws IllegalArgumentException com mensagem para o usuário se o pedido não fizer sentido
     */
    List<String> comandos(Map<String, String> f, Map<String, Object> st, boolean corComoTexto) {
        List<String> c = new ArrayList<>();
        String cor = f.getOrDefault("cor", "").trim();
        Double brilho = percentual(f.get("brilho"), 1, 100), temp = percentual(f.get("temp"), 0, 100);
        boolean branco = "branco".equals(f.get("modo"));
        Map<?, ?> atual = codCor == null ? null : corAtual(st.get(codCor));

        if (!cor.isEmpty()) {
            if (codCor == null) throw new IllegalArgumentException("Esta lâmpada não tem cor (só branco).");
            if (!HEX.matcher(cor).matches()) throw new IllegalArgumentException("Cor inválida.");
            double[] hsv = rgbParaHsv(cor);
            if (temModo) c.add(cmd("work_mode", "\"colour\""));
            c.add(cmdCor(hsv[0], hsv[1], brilho != null ? brilho : brilhoPct(st, atual), corComoTexto));
        } else if (temp != null || branco) {
            if (temModo) c.add(cmd("work_mode", "\"white\""));
            if (temp != null) {
                if (codTemp == null) throw new IllegalArgumentException("Esta lâmpada não ajusta a temperatura do branco.");
                c.add(cmd(codTemp, String.valueOf(Math.round(tMin + temp / 100 * (tMax - tMin)))));
            }
            if (brilho != null && codBrilho != null) c.add(cmd(codBrilho, escalaBrilho(brilho)));
        } else if (brilho != null) {
            boolean emCor = codCor != null && "colour".equals(String.valueOf(st.get("work_mode")));
            if (emCor || codBrilho == null) {            // em modo cor (ou lâmpada só colorida) o brilho é o "V" da cor
                if (codCor == null) throw new IllegalArgumentException("Esta lâmpada não ajusta o brilho.");
                double h = atual == null ? 0 : num(atual.get("h"), 0) / hMax * 360;
                double s = atual == null ? 0 : pct(num(atual.get("s"), 0), sMin, sMax) / 100;
                c.add(cmdCor(h, s, brilho, corComoTexto));
            } else {
                c.add(cmd(codBrilho, escalaBrilho(brilho)));
            }
        } else {
            throw new IllegalArgumentException("Nada para alterar.");
        }
        return c;
    }

    // ---------- conversões ----------

    private String escalaBrilho(double pct) { return String.valueOf(Math.round(bMin + pct / 100 * (bMax - bMin))); }

    private String cmdCor(double hGraus, double sFrac, double vPct, boolean texto) {
        long h = Math.max(0, Math.min(Math.round(hMax), Math.round(hGraus / 360 * hMax)));
        long s = Math.round(sMin + Math.max(0, Math.min(1, sFrac)) * (sMax - sMin));
        long v = Math.round(vMin + Math.max(0, Math.min(100, vPct)) / 100 * (vMax - vMin));
        String obj = "{\"h\":" + h + ",\"s\":" + s + ",\"v\":" + v + "}";
        return cmd(codCor, texto ? "\"" + Servidor.escaparJson(obj) + "\"" : obj);
    }

    private double brilhoPct(Map<String, Object> st, Map<?, ?> cor) {
        boolean emCor = cor != null && "colour".equals(String.valueOf(st.get("work_mode")));
        if (emCor) return pct(num(cor.get("v"), vMax), vMin, vMax);
        if (codBrilho != null && st.get(codBrilho) instanceof Number) return pct(num(st.get(codBrilho), bMin), bMin, bMax);
        if (cor != null) return pct(num(cor.get("v"), vMax), vMin, vMax);
        return 100;
    }

    private static String cmd(String codigo, String valorJson) {
        return "{\"code\":\"" + Servidor.escaparJson(codigo) + "\",\"value\":" + valorJson + "}";
    }

    private static Map<?, ?> corAtual(Object o) {
        if (o instanceof Map) return (Map<?, ?>) o;
        if (o instanceof String && !((String) o).isEmpty()) {
            Object p = TuyaClient.parseJson((String) o);
            if (p instanceof Map) return (Map<?, ?>) p;
        }
        return null;
    }

    private static String primeiro(Map<String, Object> st, String... codigos) {
        for (String c : codigos) if (st.containsKey(c)) return c;
        return null;
    }

    private static double num(Object o, double padrao) { return o instanceof Number ? ((Number) o).doubleValue() : padrao; }

    private static double faixa(Map<String, Object> sp, String codigo, String chave, double padrao) {
        Object m = codigo == null ? null : sp.get(codigo);
        return m instanceof Map ? num(((Map<?, ?>) m).get(chave), padrao) : padrao;
    }

    private static double faixaCor(Map<String, Object> sp, String codigo, String canal, String chave, double padrao) {
        Object m = codigo == null ? null : sp.get(codigo);
        if (m instanceof Map) {
            Object c = ((Map<?, ?>) m).get(canal);
            if (c instanceof Map) return num(((Map<?, ?>) c).get(chave), padrao);
        }
        return padrao;
    }

    private static double pct(double v, double min, double max) {
        return max <= min ? 100 : Math.max(0, Math.min(100, (v - min) / (max - min) * 100));
    }

    private static Double percentual(String s, double min, double max) {
        if (s == null || s.trim().isEmpty()) return null;
        try { return Math.max(min, Math.min(max, Double.parseDouble(s.trim().replace(',', '.')))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Valor inválido."); }
    }

    /** #rrggbb -> {matiz 0-360, saturação 0-1, valor 0-1} */
    static double[] rgbParaHsv(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        double r = Integer.parseInt(h.substring(0, 2), 16) / 255.0, g = Integer.parseInt(h.substring(2, 4), 16) / 255.0,
                b = Integer.parseInt(h.substring(4, 6), 16) / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, matiz;
        if (d == 0) matiz = 0;
        else if (max == r) matiz = 60 * (((g - b) / d) % 6);
        else if (max == g) matiz = 60 * ((b - r) / d + 2);
        else matiz = 60 * ((r - g) / d + 4);
        if (matiz < 0) matiz += 360;
        return new double[]{matiz, max == 0 ? 0 : d / max, max};
    }

    static String hex(double hGraus, double s, double v) {
        double c = v * s, x = c * (1 - Math.abs((hGraus / 60) % 2 - 1)), m = v - c, r, g, b;
        int sec = (int) (((hGraus % 360) + 360) % 360 / 60);
        switch (sec) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x;
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }
}
