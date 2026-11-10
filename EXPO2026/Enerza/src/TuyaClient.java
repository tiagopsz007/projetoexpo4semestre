import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente da Tuya Cloud (OpenAPI) - é o que o app Smart Life usa por trás.
 * Fluxo: pede um token (assinado com HMAC-SHA256) e depois chama os endpoints de aparelho.
 * Documentação: https://developer.tuya.com/en/docs/iot/new-singnature
 *
 * Só o Java conhece Client ID / Secret; o navegador nunca vê nada disso.
 */
class TuyaClient {

    /** Erro devolvido pela Tuya (success=false), com o código numérico dela. */
    static class TuyaException extends IOException {
        final int codigo;
        TuyaException(int codigo, String msg) { super(msg); this.codigo = codigo; }
    }

    /** Estado de um aparelho: online? + pontos de dados (switch_1, cur_power...). */
    static class Estado {
        final boolean online; final Map<String, Object> status;
        Estado(boolean online, Map<String, Object> status) { this.online = online; this.status = status; }
    }


    private final String endpoint, clientId, secret, uid;
    private final int intervalo;      // de quantos em quantos segundos a página consulta
    private final long cacheMs;       // evita gastar chamadas da API (cota gratuita) com várias abas/usuários
    private String accessToken;
    private long expiraEm;
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();   // id -> {momento, Estado}

    TuyaClient(Properties cfg) {
        endpoint = cfg.getProperty("tuya.endpoint", "https://openapi.tuyaus.com").trim().replaceAll("/+$", "");
        clientId = cfg.getProperty("tuya.client.id", "").trim();
        secret = cfg.getProperty("tuya.client.secret", "").trim();
        uid = cfg.getProperty("tuya.uid", "").trim();
        int seg;
        try { seg = Integer.parseInt(cfg.getProperty("tuya.atualizar.segundos", "10").trim()); } catch (NumberFormatException e) { seg = 10; }
        intervalo = Math.max(2, Math.min(seg, 300));
        cacheMs = intervalo * 500L;
    }

    boolean ativo() { return !clientId.isEmpty() && !secret.isEmpty(); }

    boolean temUid() { return !uid.isEmpty(); }

    int intervalo() { return intervalo; }

    // ---------- operações ----------

    /** Estado atual (online + pontos de dados) de um aparelho. */
    Estado estado(String deviceId) throws IOException {
        Object[] c = cache.get(deviceId);
        if (c != null && System.currentTimeMillis() - (Long) c[0] < cacheMs) return (Estado) c[1];

        Map<String, Object> r = mapa(chamar("GET", "/v1.0/devices/" + deviceId, ""));
        Map<String, Object> st = new LinkedHashMap<>();
        if (r.get("status") instanceof List)
            for (Object o : (List<?>) r.get("status"))
                if (o instanceof Map) st.put(String.valueOf(((Map<?, ?>) o).get("code")), ((Map<?, ?>) o).get("value"));
        Estado e = new Estado(Boolean.TRUE.equals(r.get("online")), st);
        cache.put(deviceId, new Object[]{System.currentTimeMillis(), e});
        return e;
    }

    /** Liga/desliga: comando {"code":"switch_1","value":true}. */
    void comando(String deviceId, String codigo, boolean valor) throws IOException {
        String corpo = "{\"commands\":[{\"code\":\"" + Servidor.escaparJson(codigo) + "\",\"value\":" + valor + "}]}";
        chamar("POST", "/v1.0/devices/" + deviceId + "/commands", corpo);
        cache.remove(deviceId);
    }

    /** Envia vários comandos de uma vez. Cada item já é um JSON {"code":"...","value":...}. */
    void comandos(String deviceId, List<String> comandosJson) throws IOException {
        chamar("POST", "/v1.0/devices/" + deviceId + "/commands", "{\"commands\":[" + String.join(",", comandosJson) + "]}");
        cache.remove(deviceId);
    }

    private final Map<String, Object[]> specs = new ConcurrentHashMap<>();   // id -> {momento, mapa}

    /**
     * Faixas de valores que o aparelho aceita (ex.: bright_value_v2 -> {min:10,max:1000};
     * colour_data_v2 -> {h:{max:360},s:{max:1000},v:{max:1000}}). Guardado em memória.
     * Se a Tuya não responder, devolve vazio e o chamador usa valores padrão.
     */
    Map<String, Object> especificacoes(String deviceId) {
        Object[] c = specs.get(deviceId);
        if (c != null) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) c[1];
            if (!m.isEmpty() || System.currentTimeMillis() - (Long) c[0] < 300_000) return m;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> r = mapa(chamar("GET", "/v1.0/devices/" + deviceId + "/specifications", ""));
            if (r.get("functions") instanceof List)
                for (Object o : (List<?>) r.get("functions"))
                    if (o instanceof Map && ((Map<?, ?>) o).get("values") instanceof String) {
                        Object v = parseJson((String) ((Map<?, ?>) o).get("values"));
                        if (v instanceof Map) out.put(String.valueOf(((Map<?, ?>) o).get("code")), v);
                    }
        } catch (IOException e) { /* usa os padrões */ }
        specs.put(deviceId, new Object[]{System.currentTimeMillis(), out});
        return out;
    }

    /** Converte texto JSON em Map/List/String/Double/Boolean, ou null se for inválido. */
    static Object parseJson(String texto) {
        try { return Json.parse(texto); } catch (IOException e) { return null; }
    }

    /** Aparelhos da conta Smart Life vinculada ao projeto (precisa de tuya.uid). */
    List<Map<String, Object>> aparelhosDaConta() throws IOException {
        Object r = chamar("GET", "/v1.0/users/" + uid + "/devices", "");
        List<Map<String, Object>> out = new ArrayList<>();
        if (r instanceof List)
            for (Object o : (List<?>) r)
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                    out.add(m);
                }
        return out;
    }

    /** Descobre qual ponto de dados liga/desliga: switch_1 (tomadas), switch (alguns), switch_led (lâmpadas)... */
    static String codigoSwitch(Map<String, Object> st, String preferido) {
        if (st.get(preferido) instanceof Boolean) return preferido;
        for (String c : new String[]{"switch_1", "switch", "switch_led"})
            if (st.get(c) instanceof Boolean) return c;
        for (Map.Entry<String, Object> e : st.entrySet())
            if (e.getKey().startsWith("switch") && e.getValue() instanceof Boolean) return e.getKey();
        return null;
    }

    // ---------- HTTP assinado ----------

    private synchronized String token() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < expiraEm) return accessToken;
        Map<String, Object> r = mapa(requisicao("GET", "/v1.0/token?grant_type=1", "", null));
        accessToken = String.valueOf(r.get("access_token"));
        double dur = r.get("expire_time") instanceof Number ? ((Number) r.get("expire_time")).doubleValue() : 7200;
        expiraEm = System.currentTimeMillis() + (long) (dur - 120) * 1000L;
        return accessToken;
    }

    /** Chama um endpoint de negócio (com token) e devolve o campo "result" já convertido. Renova o token se expirou. */
    private Object chamar(String metodo, String caminho, String corpo) throws IOException {
        if (!ativo()) throw new IOException("Tuya não configurada (tuya.client.id / tuya.client.secret)");
        try {
            return requisicao(metodo, caminho, corpo, token());
        } catch (TuyaException e) {
            if (e.codigo != 1010 && e.codigo != 1011) throw e;      // token inválido/expirado -> renova e tenta uma vez
            synchronized (this) { accessToken = null; }
            return requisicao(metodo, caminho, corpo, token());
        }
    }

    private Object requisicao(String metodo, String caminho, String corpo, String tokenAcesso) throws IOException {
        String t = String.valueOf(System.currentTimeMillis());
        String paraAssinar = metodo + "\n" + sha256Hex(corpo) + "\n\n" + caminho;
        String assinatura = hmac(clientId + (tokenAcesso == null ? "" : tokenAcesso) + t + paraAssinar);

        HttpURLConnection c = (HttpURLConnection) new URL(endpoint + caminho).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(10000);
        c.setRequestMethod(metodo);
        c.setRequestProperty("client_id", clientId);
        c.setRequestProperty("sign", assinatura);
        c.setRequestProperty("t", t);
        c.setRequestProperty("sign_method", "HMAC-SHA256");
        if (tokenAcesso != null) c.setRequestProperty("access_token", tokenAcesso);
        c.setRequestProperty("Content-Type", "application/json");
        if (!corpo.isEmpty()) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) { os.write(corpo.getBytes(StandardCharsets.UTF_8)); }
        }
        String txt;
        try {
            int status = c.getResponseCode();
            txt = new String(Servidor.lerTudo(status >= 400 ? c.getErrorStream() : c.getInputStream()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("não consegui falar com a Tuya (" + endpoint + ")");
        }

        Map<String, Object> resp = mapa(Json.parse(txt));
        if (!Boolean.TRUE.equals(resp.get("success"))) {
            int codigo = resp.get("code") instanceof Number ? ((Number) resp.get("code")).intValue() : 0;
            throw new TuyaException(codigo, traduzir(codigo, String.valueOf(resp.get("msg"))));
        }
        return resp.get("result");
    }

    private static String traduzir(int codigo, String msg) {
        switch (codigo) {
            case 1004: return "assinatura recusada (confira tuya.client.id e tuya.client.secret)";
            case 1106: return "sem permissão para este aparelho (vincule a conta Smart Life ao projeto e autorize o IoT Core)";
            case 2001: return "aparelho offline";
            case 2008: return "o aparelho não aceita esse comando";
            case 28841001: return "o serviço IoT Core não está assinado neste projeto (platform.tuya.com > Cloud > Development > seu projeto > Service API > autorizar IoT Core)";
            case 28841002: case 28841107: return "assinatura IoT Core expirada - renove em platform.tuya.com > Cloud > Service API";
            case 1109: case 1100: return "parâmetro inválido (device id correto?)";
            case 2009: return "aparelho não encontrado na conta vinculada";
            default: return "Tuya: " + msg + " (código " + codigo + ")";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object o) throws IOException {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IOException("resposta inesperada da Tuya");
    }

    private String hmac(String texto) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().withUpperCase().formatHex(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IOException("falha ao assinar a requisição"); }
    }

    private static String sha256Hex(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    // ---------- mini parser de JSON (o projeto não usa bibliotecas) ----------

    private static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        static Object parse(String texto) throws IOException {
            try { return new Json(texto).valor(); }
            catch (RuntimeException e) { throw new IOException("resposta inválida da Tuya"); }
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object valor() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = texto(); ws(); i++;            // pula ':'
                    m.put(k, valor()); ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(valor()); ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return texto();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int ini = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(ini, i));
        }

        private String texto() {
            StringBuilder sb = new StringBuilder();
            i++;                                                    // abre aspas
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }
    }
}
