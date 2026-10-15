import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Liga/desliga aparelhos Xiaomi (lâmpadas, tomadas, purificadores...) direto pela rede local.
 *
 * GET  /api/xiaomi/dispositivos          -> lista com o estado de cada aparelho
 * POST /api/xiaomi/ligar?id=luz&valor=true -> liga (true) ou desliga (false)
 */
class XiaomiService {

    private static final Pattern VALOR_MIOT = Pattern.compile("\"value\"\\s*:\\s*(true|false)");
    private static final Pattern VALOR_LEGADO = Pattern.compile("\"result\"\\s*:\\s*\\[\\s*\"(on|off)\"");

    private static class Dispositivo {
        String id, nome, icone, protocolo, erroConfig;
        int siid, piid;
        MiioClient cliente;
    }

    private final Map<String, Dispositivo> dispositivos = new LinkedHashMap<>();
    private final HomeAssistantService ha;

    XiaomiService(Properties cfg) {
        ha = new HomeAssistantService(cfg);
        for (String id : cfg.getProperty("xiaomi.dispositivos", "").split(",")) {
            id = id.trim();
            if (id.isEmpty()) continue;
            String p = "xiaomi." + id + ".";
            Dispositivo d = new Dispositivo();
            d.id = id;
            d.nome = cfg.getProperty(p + "nome", id);
            d.icone = cfg.getProperty(p + "icone", "🔌");
            d.protocolo = cfg.getProperty(p + "protocolo", "miot").trim().toLowerCase();
            d.siid = Integer.parseInt(cfg.getProperty(p + "siid", "2").trim());
            d.piid = Integer.parseInt(cfg.getProperty(p + "piid", "1").trim());
            try {
                d.cliente = new MiioClient(cfg.getProperty(p + "ip", "").trim(), cfg.getProperty(p + "token", ""));
            } catch (Exception e) {
                d.erroConfig = "configuração inválida: " + e.getMessage();
            }
            dispositivos.put(id, d);
        }
        System.out.println("Xiaomi: " + dispositivos.size() + " aparelho(s) configurado(s).");
    }

    void tratar(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/api/xiaomi/dispositivos")) listar(ex);
            else if (p.equals("/api/xiaomi/ligar") && "POST".equals(ex.getRequestMethod())) ligar(ex);
            else Servidor.json(ex, 404, "{\"erro\":\"rota inexistente\"}");
        } finally {
            ex.close();
        }
    }

    private void listar(HttpExchange ex) throws IOException {
        // consulta todos os aparelhos em paralelo para a página não travar
        List<CompletableFuture<String>> futuros = new ArrayList<>();
        if (ha.ativo()) futuros.add(CompletableFuture.supplyAsync(ha::listarItens));
        for (Dispositivo d : dispositivos.values()) futuros.add(CompletableFuture.supplyAsync(() -> paraJson(d)));
        Servidor.json(ex, 200, "[" + futuros.stream().map(CompletableFuture::join)
                .filter(s -> !s.isEmpty()).collect(Collectors.joining(",")) + "]");
    }

    private String paraJson(Dispositivo d) {
        String ligado = "null", erro = d.erroConfig;
        if (erro == null) {
            try { ligado = String.valueOf(lerEstado(d)); }
            catch (Exception e) { erro = e.getMessage(); }
        }
        return "{\"id\":\"" + Servidor.escaparJson(d.id) + "\",\"nome\":\"" + Servidor.escaparJson(d.nome)
                + "\",\"icone\":\"" + Servidor.escaparJson(d.icone) + "\",\"ligado\":" + ligado
                + (erro == null ? "" : ",\"erro\":\"" + Servidor.escaparJson(erro) + "\"") + "}";
    }

    private void ligar(HttpExchange ex) throws IOException {
        Map<String, String> q = Servidor.query(ex);
        String id = q.get("id") == null ? "" : q.get("id");
        if (id.startsWith("ha:")) {                       // aparelho vindo do Home Assistant (Xiaomi Home)
            boolean valor = "true".equals(q.get("valor"));
            try {
                ha.ligar(id.substring(3), valor);
                Servidor.json(ex, 200, "{\"ok\":true,\"ligado\":" + valor + "}");
            } catch (IOException e) {
                Servidor.json(ex, 502, "{\"erro\":\"" + Servidor.escaparJson(e.getMessage()) + "\"}");
            }
            return;
        }
        Dispositivo d = dispositivos.get(id);
        if (d == null) { Servidor.json(ex, 404, "{\"erro\":\"aparelho não encontrado\"}"); return; }
        if (d.erroConfig != null) { Servidor.json(ex, 500, "{\"erro\":\"" + Servidor.escaparJson(d.erroConfig) + "\"}"); return; }
        boolean valor = "true".equals(q.get("valor"));
        try {
            String r = "legado".equals(d.protocolo)
                    ? d.cliente.enviar("set_power", "[\"" + (valor ? "on" : "off") + "\"]")
                    : d.cliente.enviar("set_properties", "[{\"did\":\"" + d.id + "\",\"siid\":" + d.siid
                            + ",\"piid\":" + d.piid + ",\"value\":" + valor + "}]");
            if (r.contains("\"error\"")) throw new IOException("aparelho recusou o comando: " + r);
            Servidor.json(ex, 200, "{\"ok\":true,\"ligado\":" + valor + "}");
        } catch (IOException e) {
            Servidor.json(ex, 502, "{\"erro\":\"" + Servidor.escaparJson(e.getMessage()) + "\"}");
        }
    }

    private boolean lerEstado(Dispositivo d) throws IOException {
        if ("legado".equals(d.protocolo)) {
            String r = d.cliente.enviar("get_prop", "[\"power\"]");
            Matcher m = VALOR_LEGADO.matcher(r);
            if (m.find()) return m.group(1).equals("on");
            throw new IOException("resposta inesperada: " + r);
        }
        String r = d.cliente.enviar("get_properties",
                "[{\"did\":\"" + d.id + "\",\"siid\":" + d.siid + ",\"piid\":" + d.piid + "}]");
        Matcher m = VALOR_MIOT.matcher(r);
        if (m.find()) return Boolean.parseBoolean(m.group(1));
        throw new IOException("resposta inesperada (confira siid/piid): " + r);
    }
}
