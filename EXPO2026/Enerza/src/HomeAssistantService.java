import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Integração com a integração oficial "Xiaomi Home" (https://github.com/XiaoMi/ha_xiaomi_home).
 * Ela é um plugin Python do Home Assistant, então o Java conversa com o Home Assistant pela API REST:
 * - lista as entidades da integração xiaomi_home (via /api/template)
 * - liga/desliga com o serviço homeassistant.turn_on / turn_off
 */
class HomeAssistantService {

    private static final Pattern ENTIDADE = Pattern.compile("^[a-z_]+\\.[a-z0-9_]+$");

    // Monta no próprio Home Assistant a lista já no formato que a página usa:
    // {"id":"ha:light.x","nome":"...","icone":"💡","ligado":true|false|null,"erro"?:"..."}
    private static final String TEMPLATE =
            "{% set icones = {'light':'💡','switch':'🔌','fan':'🌀','humidifier':'💧','climate':'❄️',"
          + "'vacuum':'🧹','media_player':'📺','water_heater':'🚿','cover':'🪟'} %}"
          + "{% set ligados = ['on','heat','cool','auto','dry','fan_only','heat_cool','cleaning','playing','open','eco','electric','performance'] %}"
          + "[{% for e in integration_entities('%INTEGRACAO%') if e.split('.')[0] in icones %}"
          + "{% set s = states(e) %}"
          + "{\"id\":{{ ('ha:' ~ e) | to_json }},\"nome\":{{ (state_attr(e,'friendly_name') or e) | to_json }},"
          + "\"icone\":{{ icones[e.split('.')[0]] | to_json }},"
          + "{% if s in ['unavailable','unknown'] %}\"ligado\":null,\"erro\":\"indisponível\""
          + "{% else %}\"ligado\":{{ (s in ligados) | to_json }}{% endif %}}"
          + "{{ ',' if not loop.last }}{% endfor %}]";

    private final String url, token, integracao;

    HomeAssistantService(Properties cfg) {
        url = cfg.getProperty("ha.url", "").trim().replaceAll("/+$", "");
        token = cfg.getProperty("ha.token", "").trim();
        integracao = cfg.getProperty("ha.integracao", "xiaomi_home").trim();
        if (ativo()) System.out.println("Home Assistant: usando " + url + " (integração " + integracao + ")");
    }

    boolean ativo() {
        return !url.isEmpty() && !token.isEmpty();
    }

    /** Devolve os itens JSON separados por vírgula (sem os colchetes), ou "" se não houver. */
    String listarItens() {
        try {
            String corpo = "{\"template\":\"" + Servidor.escaparJson(TEMPLATE.replace("%INTEGRACAO%", integracao)) + "\"}";
            String r = chamar("/api/template", corpo).trim();
            if (!r.startsWith("[") || !r.endsWith("]")) throw new IOException("resposta inesperada do Home Assistant");
            return r.substring(1, r.length() - 1).trim();
        } catch (IOException e) {
            return "{\"id\":\"ha\",\"nome\":\"Home Assistant\",\"icone\":\"🏠\",\"ligado\":null,\"erro\":\""
                    + Servidor.escaparJson(e.getMessage()) + "\"}";
        }
    }

    void ligar(String entidade, boolean valor) throws IOException {
        if (!ENTIDADE.matcher(entidade).matches()) throw new IOException("entidade inválida");
        chamar("/api/services/homeassistant/" + (valor ? "turn_on" : "turn_off"), "{\"entity_id\":\"" + entidade + "\"}");
    }

    private String chamar(String path, String json) throws IOException {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) new URL(url + path).openConnection();
        } catch (IOException e) {
            throw new IOException("ha.url inválida: " + url);
        }
        c.setConnectTimeout(3000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Home Assistant não respondeu em " + url);
        }
        int status = c.getResponseCode();
        byte[] b = Servidor.lerTudo(status >= 400 ? c.getErrorStream() : c.getInputStream());
        String corpo = new String(b, StandardCharsets.UTF_8);
        if (status == 401) throw new IOException("ha.token inválido (crie um token de longa duração)");
        if (status >= 400) throw new IOException("Home Assistant respondeu " + status + ": " + corpo);
        return corpo;
    }
}
