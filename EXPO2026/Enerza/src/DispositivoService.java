import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Aparelhos da família (tomadas/lâmpadas Smart Life / Tuya), guardados na tabela "dispositivos" do MySQL.
 * Todos da mesma família enxergam e ligam/desligam; só o admin adiciona e remove.
 *
 * GET  /api/dispositivos/lista                       -> aparelhos da família com estado ao vivo
 * GET  /api/dispositivos/descobrir                   -> (admin) aparelhos da conta Smart Life ainda não cadastrados
 * POST /api/dispositivos/adicionar (nome, icone, comodo, tuyaId, potencia)  (admin)
 * POST /api/dispositivos/remover   (id)                              (admin)
 * POST /api/dispositivos/editar    (id, comodo)                      (admin)
 * POST /api/dispositivos/ligar     (id, valor=true|false)
 * POST /api/dispositivos/luz       (id + cor=#rrggbb | brilho=1-100 | temp=0-100 | modo=branco)   lâmpadas
 */
class DispositivoService {

    private static final Set<String> COMODOS = Set.of("sala", "cozinha", "quarto", "banheiro");
    private static final Pattern TUYA_ID = Pattern.compile("^[A-Za-z0-9]{6,64}$");
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tuya"); t.setDaemon(true); return t;
    });

    private static class Linha {
        long id; String nome, icone, comodo, tuyaId, codigo; int potencia;
    }

    private final AuthService auth;
    private final TuyaClient tuya;

    DispositivoService(Properties cfg, AuthService auth) throws SQLException {
        this.auth = auth;
        this.tuya = new TuyaClient(cfg);
        try (Connection c = auth.conectar(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS dispositivos ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "familia_id BIGINT NOT NULL,"
                    + "nome VARCHAR(60) NOT NULL,"
                    + "icone VARCHAR(16) NOT NULL DEFAULT '🔌',"
                    + "comodo VARCHAR(20) NOT NULL DEFAULT 'sala',"
                    + "tuya_id VARCHAR(64) NOT NULL,"
                    + "codigo_switch VARCHAR(30) NOT NULL DEFAULT 'switch_1',"
                    + "potencia_w INT NOT NULL DEFAULT 0,"
                    + "criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "UNIQUE KEY uq_familia_tuya (familia_id, tuya_id),"
                    + "FOREIGN KEY (familia_id) REFERENCES familias(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            try {   // tabela criada antes de existir o cômodo: acrescenta a coluna
                s.executeUpdate("ALTER TABLE dispositivos ADD COLUMN comodo VARCHAR(20) NOT NULL DEFAULT 'sala' AFTER icone");
            } catch (SQLException e) {
                if (e.getErrorCode() != 1060) throw e;      // 1060 = coluna já existe
            }
        }
        System.out.println(tuya.ativo() ? "Tuya: credenciais carregadas."
                : "Tuya: preencha tuya.client.id e tuya.client.secret no config.properties.");
    }

    void tratar(HttpExchange ex) throws IOException {
        try {
            AuthService.Usuario u = auth.usuarioDaSessao(ex);
            if (u == null) { erro(ex, 401, "não autenticado"); return; }
            String p = ex.getRequestURI().getPath();
            boolean post = "POST".equals(ex.getRequestMethod());
            if (p.equals("/api/dispositivos/lista")) lista(ex, u);
            else if (p.equals("/api/dispositivos/descobrir")) descobrir(ex, u);
            else if (p.equals("/api/dispositivos/adicionar") && post) adicionar(ex, u);
            else if (p.equals("/api/dispositivos/remover") && post) remover(ex, u);
            else if (p.equals("/api/dispositivos/editar") && post) editar(ex, u);
            else if (p.equals("/api/dispositivos/ligar") && post) ligar(ex, u);
            else if (p.equals("/api/dispositivos/luz") && post) luz(ex, u);
            else Servidor.json(ex, 404, "{\"erro\":\"rota inexistente\"}");
        } catch (SQLException e) {
            e.printStackTrace();
            erro(ex, 503, "Banco de dados indisponível. Tente novamente.");
        } catch (Exception e) {
            e.printStackTrace();
            erro(ex, 500, "Erro interno.");
        } finally {
            ex.close();
        }
    }

    // ---------- rotas ----------

    private void lista(HttpExchange ex, AuthService.Usuario u) throws IOException, SQLException {
        List<Linha> linhas = carregar(u.familiaId);
        List<CompletableFuture<String>> fs = new ArrayList<>();
        for (Linha l : linhas) fs.add(CompletableFuture.supplyAsync(() -> paraJson(l), POOL));   // consulta todos em paralelo
        String itens = fs.stream().map(CompletableFuture::join).collect(Collectors.joining(","));
        Servidor.json(ex, 200, "{\"configurado\":" + tuya.ativo() + ",\"souAdmin\":" + "admin".equals(u.cargo)
                + ",\"intervalo\":" + tuya.intervalo() + ",\"podeDescobrir\":" + (tuya.ativo() && tuya.temUid()) + ",\"dispositivos\":[" + itens + "]}");
    }

    private String paraJson(Linha l) {
        String ligado = "null", watts = "null", erro = null, luz = "";
        if (!tuya.ativo()) erro = "Tuya não configurada no config.properties";
        else {
            try {
                TuyaClient.Estado e = tuya.estado(l.tuyaId);
                String cod = TuyaClient.codigoSwitch(e.status, l.codigo);
                if (!e.online) erro = "offline";
                else if (cod == null) erro = "sem interruptor liga/desliga";
                else ligado = String.valueOf(e.status.get(cod));
                if (e.online) {
                    LuzTuya z = LuzTuya.de(e.status, tuya.especificacoes(l.tuyaId));
                    if (z != null) luz = ",\"luz\":" + z.estadoJson(e.status);
                }
                // tomadas com medição informam cur_power em décimos de watt
                if (e.online && Boolean.TRUE.equals(e.status.get(cod)) && e.status.get("cur_power") instanceof Number)
                    watts = String.format(Locale.ROOT, "%.1f", ((Number) e.status.get("cur_power")).doubleValue() / 10.0);
            } catch (IOException e) {
                erro = e.getMessage();
            }
        }
        return "{\"id\":" + l.id + ",\"nome\":\"" + Servidor.escaparJson(l.nome) + "\",\"icone\":\"" + Servidor.escaparJson(l.icone)
                + "\",\"comodo\":\"" + l.comodo + "\",\"potencia\":" + l.potencia + ",\"ligado\":" + ligado + ",\"watts\":" + watts
                + luz + (erro == null ? "" : ",\"erro\":\"" + Servidor.escaparJson(erro) + "\"") + "}";
    }

    private void ligar(HttpExchange ex, AuthService.Usuario u) throws IOException, SQLException {
        Map<String, String> f = Servidor.formulario(ex);
        Linha l = buscar(numero(f.get("id")), u.familiaId);
        if (l == null) { erro(ex, 404, "Aparelho não encontrado."); return; }
        boolean valor = "true".equals(f.get("valor"));
        try {
            try {
                tuya.comando(l.tuyaId, l.codigo, valor);
            } catch (TuyaClient.TuyaException e) {
                if (e.codigo != 2008) throw e;                       // "comando não suportado": o código do interruptor deve ser outro
                String certo = TuyaClient.codigoSwitch(tuya.estado(l.tuyaId).status, l.codigo);
                if (certo == null || certo.equals(l.codigo)) throw e;
                tuya.comando(l.tuyaId, certo, valor);
                salvarCodigo(l.id, certo);
            }
            Servidor.json(ex, 200, "{\"ok\":true,\"ligado\":" + valor + "}");
        } catch (IOException e) {
            erro(ex, 502, e.getMessage());
        }
    }

    /** Ajusta cor, brilho ou temperatura do branco de uma lâmpada. */
    private void luz(HttpExchange ex, AuthService.Usuario u) throws IOException, SQLException {
        Map<String, String> f = Servidor.formulario(ex);
        Linha l = buscar(numero(f.get("id")), u.familiaId);
        if (l == null) { erro(ex, 404, "Aparelho não encontrado."); return; }
        try {
            TuyaClient.Estado e = tuya.estado(l.tuyaId);
            LuzTuya z = LuzTuya.de(e.status, tuya.especificacoes(l.tuyaId));
            if (z == null) { erro(ex, 400, "Esse aparelho não tem controle de cor ou brilho."); return; }
            try {
                try {
                    tuya.comandos(l.tuyaId, z.comandos(f, e.status, true));
                } catch (TuyaClient.TuyaException t) {
                    if (t.codigo != 2008) throw t;                 // algumas lâmpadas querem colour_data como objeto, não como texto
                    tuya.comandos(l.tuyaId, z.comandos(f, e.status, false));
                }
            } catch (IllegalArgumentException v) {
                erro(ex, 400, v.getMessage()); return;
            }
            Servidor.json(ex, 200, "{\"ok\":true}");
        } catch (IOException e) {
            erro(ex, 502, e.getMessage());
        }
    }

    private void adicionar(HttpExchange ex, AuthService.Usuario u) throws IOException, SQLException {
        if (!"admin".equals(u.cargo)) { erro(ex, 403, "Só o administrador pode adicionar aparelhos."); return; }
        Map<String, String> f = Servidor.formulario(ex);
        String nome = limpar(f.get("nome"), 60);
        String icone = limpar(f.get("icone"), 8);
        String comodo = f.getOrDefault("comodo", "").trim().toLowerCase();
        String tuyaId = f.getOrDefault("tuyaId", "").trim();
        String potTxt = f.getOrDefault("potencia", "").trim();
        long potencia = potTxt.isEmpty() ? 0 : numero(potTxt);
        if (nome.isEmpty()) { erro(ex, 400, "Informe o nome do aparelho."); return; }
        if (icone.isEmpty()) icone = "🔌";
        if (!COMODOS.contains(comodo)) { erro(ex, 400, "Escolha o cômodo do aparelho."); return; }
        if (!TUYA_ID.matcher(tuyaId).matches()) { erro(ex, 400, "Device ID inválido (letras e números, copie do painel da Tuya)."); return; }
        if (potencia < 0 || potencia > 20000) { erro(ex, 400, "Potência deve ficar entre 0 e 20000 W."); return; }
        if (!tuya.ativo()) { erro(ex, 400, "Configure tuya.client.id e tuya.client.secret no config.properties primeiro."); return; }

        // confirma que a Tuya enxerga o aparelho e descobre qual ponto de dados liga/desliga
        String codigo;
        try {
            codigo = TuyaClient.codigoSwitch(tuya.estado(tuyaId).status, "switch_1");
        } catch (IOException e) {
            erro(ex, 400, "Não consegui acessar esse aparelho na Tuya: " + e.getMessage()); return;
        }
        if (codigo == null) { erro(ex, 400, "Esse aparelho não tem interruptor de liga/desliga."); return; }

        try (Connection c = auth.conectar(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dispositivos (familia_id, nome, icone, comodo, tuya_id, codigo_switch, potencia_w) VALUES (?,?,?,?,?,?,?)")) {
            ps.setLong(1, u.familiaId); ps.setString(2, nome); ps.setString(3, icone);
            ps.setString(4, comodo); ps.setString(5, tuyaId); ps.setString(6, codigo); ps.setInt(7, (int) potencia);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) { erro(ex, 409, "Esse aparelho já está cadastrado na sua família."); return; }
            throw e;
        }
        Servidor.json(ex, 201, "{\"ok\":true}");
    }

    private void remover(HttpExchange ex, AuthService.Usuario u) throws IOException, SQLException {
        if (!"admin".equals(u.cargo)) { erro(ex, 403, "Só o administrador pode remover aparelhos."); return; }
        long id = numero(Servidor.formulario(ex).get("id"));
        int n;
        try (Connection c = auth.conectar(); PreparedStatement ps = c.prepareStatement("DELETE FROM dispositivos WHERE id = ? AND familia_id = ?")) {
            ps.setLong(1, id); ps.setLong(2, u.familiaId);
            n = ps.executeUpdate();
        }
        if (n == 0) { erro(ex, 404, "Aparelho não encontrado."); return; }
        Servidor.json(ex, 200, "{\"ok\":true}");
    }

    private void editar(HttpExchange ex, AuthService.Usuario u) throws IOException, SQLException {
        if (!"admin".equals(u.cargo)) { erro(ex, 403, "Só o administrador pode alterar aparelhos."); return; }
        Map<String, String> f = Servidor.formulario(ex);
        String comodo = f.getOrDefault("comodo", "").trim().toLowerCase();
        if (!COMODOS.contains(comodo)) { erro(ex, 400, "Cômodo inválido."); return; }
        int n;
        try (Connection c = auth.conectar(); PreparedStatement ps = c.prepareStatement("UPDATE dispositivos SET comodo = ? WHERE id = ? AND familia_id = ?")) {
            ps.setString(1, comodo); ps.setLong(2, numero(f.get("id"))); ps.setLong(3, u.familiaId);
            n = ps.executeUpdate();
        }
        if (n == 0) { erro(ex, 404, "Aparelho não encontrado."); return; }
        Servidor.json(ex, 200, "{\"ok\":true}");
    }

    /** Lista os aparelhos da conta Smart Life vinculada (para o admin escolher em vez de copiar o ID). */
    private void descobrir(HttpExchange ex, AuthService.Usuario u) throws IOException, SQLException {
        if (!"admin".equals(u.cargo)) { erro(ex, 403, "Só o administrador pode adicionar aparelhos."); return; }
        if (!tuya.ativo() || !tuya.temUid()) { erro(ex, 400, "Preencha tuya.uid no config.properties para listar os aparelhos da conta."); return; }
        List<String> jaTem = new ArrayList<>();
        for (Linha l : carregar(u.familiaId)) jaTem.add(l.tuyaId);
        try {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> d : tuya.aparelhosDaConta()) {
                String id = String.valueOf(d.get("id"));
                if (jaTem.contains(id)) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append("{\"id\":\"").append(Servidor.escaparJson(id))
                  .append("\",\"nome\":\"").append(Servidor.escaparJson(String.valueOf(d.get("name"))))
                  .append("\",\"online\":").append(Boolean.TRUE.equals(d.get("online"))).append('}');
            }
            Servidor.json(ex, 200, "{\"aparelhos\":[" + sb + "]}");
        } catch (IOException e) {
            erro(ex, 502, e.getMessage());
        }
    }

    // ---------- banco ----------

    private List<Linha> carregar(long familiaId) throws SQLException {
        List<Linha> out = new ArrayList<>();
        try (Connection c = auth.conectar(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, nome, icone, tuya_id, codigo_switch, potencia_w, comodo FROM dispositivos WHERE familia_id = ? ORDER BY id")) {
            ps.setLong(1, familiaId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(linha(rs)); }
        }
        return out;
    }

    private Linha buscar(long id, long familiaId) throws SQLException {
        try (Connection c = auth.conectar(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, nome, icone, tuya_id, codigo_switch, potencia_w, comodo FROM dispositivos WHERE id = ? AND familia_id = ?")) {
            ps.setLong(1, id); ps.setLong(2, familiaId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? linha(rs) : null; }
        }
    }

    private void salvarCodigo(long id, String codigo) {
        try (Connection c = auth.conectar(); PreparedStatement ps = c.prepareStatement("UPDATE dispositivos SET codigo_switch = ? WHERE id = ?")) {
            ps.setString(1, codigo); ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("MySQL: não consegui salvar o código do interruptor: " + e.getMessage());
        }
    }

    private static Linha linha(ResultSet rs) throws SQLException {
        Linha l = new Linha();
        l.id = rs.getLong(1); l.nome = rs.getString(2); l.icone = rs.getString(3);
        l.tuyaId = rs.getString(4); l.codigo = rs.getString(5); l.potencia = rs.getInt(6); l.comodo = rs.getString(7);
        return l;
    }

    // ---------- utilitários ----------

    private static long numero(String s) {
        try { return Long.parseLong(s == null ? "" : s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static String limpar(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.codePointCount(0, s.length()) > max ? s.substring(0, s.offsetByCodePoints(0, max)) : s;
    }

    private static void erro(HttpExchange ex, int status, String msg) throws IOException {
        Servidor.json(ex, status, "{\"erro\":\"" + Servidor.escaparJson(msg) + "\"}");
    }
}
