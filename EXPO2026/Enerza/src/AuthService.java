
import com.sun.net.httpserver.HttpExchange;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Cadastro e login de usuários, com MySQL.
 * - Tabelas "usuarios" e "sessoes" (criadas automaticamente na primeira execução)
 * - Senha: PBKDF2 + sal. Sessão: cookie "sessao" (HttpOnly, SameSite=Lax); no banco fica só o SHA-256 do token.
 *
 * POST /api/auth/cadastro  (nome, email, senha, confirmar)
 * POST /api/auth/login     (email, senha, manter)
 * POST /api/auth/logout
 * GET  /api/auth/eu        -> {"nome":..,"email":..} ou 401
 */
class AuthService {

    private static final Path ARQ_ANTIGO = Paths.get(".usuarios");
    private static final SecureRandom RND = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int ITERACOES = 120_000;
    private static final long LONGA = 30L * 24 * 3600;   // "manter conectado": 30 dias
    private static final long CURTA = 12L * 3600;        // sessão normal: 12 horas

    private static class Usuario {
        final long id; final String nome, email;
        Usuario(long id, String nome, String email) { this.id = id; this.nome = nome; this.email = email; }
    }

    private final String urlServidor, urlBanco, dbUsuario, dbSenha, dbNome;

    AuthService(Properties cfg) throws Exception {
        String host = cfg.getProperty("db.host", "localhost").trim();
        String porta = cfg.getProperty("db.porta", "3306").trim();
        dbNome = cfg.getProperty("db.nome", "enerza").trim();
        dbUsuario = cfg.getProperty("db.usuario", "root").trim();
        dbSenha = cfg.getProperty("db.senha", "");
        if (!dbNome.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("db.nome inválido (use só letras, números e _)");

        String params = "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&allowPublicKeyRetrieval=true";
        urlServidor = "jdbc:mysql://" + host + ":" + porta + "/" + params;
        urlBanco = "jdbc:mysql://" + host + ":" + porta + "/" + dbNome + params;
        iniciarBanco();
    }

    // ---------- banco ----------

    private Connection conectar() throws SQLException {
        return DriverManager.getConnection(urlBanco, dbUsuario, dbSenha);
    }

    private void iniciarBanco() throws Exception {
        try (Connection c = DriverManager.getConnection(urlServidor, dbUsuario, dbSenha); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + dbNome + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("No suitable driver"))
                throw new Exception("driver MySQL não encontrado - coloque mysql-connector-j.jar na pasta lib (o executar.bat baixa sozinho)");
            throw new Exception("não consegui conectar ao MySQL (" + e.getMessage() + "). Confira db.host, db.porta, db.usuario e db.senha no config.properties");
        }
        try (Connection c = conectar(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS usuarios ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "nome VARCHAR(60) NOT NULL,"
                    + "email VARCHAR(255) NOT NULL UNIQUE,"
                    + "senha_sal VARBINARY(16) NOT NULL,"
                    + "senha_hash VARBINARY(32) NOT NULL,"
                    + "criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sessoes ("
                    + "token_hash CHAR(64) PRIMARY KEY,"
                    + "usuario_id BIGINT NOT NULL,"
                    + "expira_em BIGINT NOT NULL,"
                    + "FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("DELETE FROM sessoes WHERE expira_em < " + System.currentTimeMillis());
            migrarArquivoAntigo(c);
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM usuarios")) {
                rs.next();
                System.out.println("MySQL: banco '" + dbNome + "' pronto, " + rs.getLong(1) + " usuário(s).");
            }
        }
    }

    /** Se existir o .usuarios da versão anterior, importa as contas para o MySQL uma única vez. */
    private void migrarArquivoAntigo(Connection c) {
        if (!Files.exists(ARQ_ANTIGO)) return;
        try {
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT IGNORE INTO usuarios (nome, email, senha_sal, senha_hash) VALUES (?,?,?,?)")) {
                Base64.Decoder d = Base64.getDecoder();
                for (String linha : Files.readAllLines(ARQ_ANTIGO, StandardCharsets.UTF_8)) {
                    String[] p = linha.split("\t");
                    if (p.length != 4) continue;
                    ps.setString(1, p[1]); ps.setString(2, p[0]);
                    ps.setBytes(3, d.decode(p[2])); ps.setBytes(4, d.decode(p[3]));
                    n += ps.executeUpdate();
                }
            }
            Files.move(ARQ_ANTIGO, Paths.get(".usuarios.migrado"), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("MySQL: " + n + " conta(s) importada(s) do .usuarios (arquivo renomeado para .usuarios.migrado).");
        } catch (Exception e) {
            System.out.println("MySQL: não consegui importar o .usuarios antigo: " + e.getMessage());
        }
    }

    // ---------- rotas ----------

    void tratar(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath();
            boolean post = "POST".equals(ex.getRequestMethod());
            if (p.equals("/api/auth/cadastro") && post) cadastro(ex);
            else if (p.equals("/api/auth/login") && post) login(ex);
            else if (p.equals("/api/auth/logout") && post) logout(ex);
            else if (p.equals("/api/auth/eu")) eu(ex);
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

    /** E-mail do usuário logado nesta requisição, ou null. */
    String emailLogado(HttpExchange ex) {
        Usuario u = usuarioDaSessao(ex);
        return u == null ? null : u.email;
    }

    private void cadastro(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> f = Servidor.formulario(ex);
        String nome = limpar(f.get("nome"));
        String email = normalizar(f.get("email"));
        String senha = f.getOrDefault("senha", "");
        String conf = f.getOrDefault("confirmar", "");

        if (nome.isEmpty()) { erro(ex, 400, "Informe seu nome."); return; }
        if (email.length() > 255 || !EMAIL.matcher(email).matches()) { erro(ex, 400, "Email inválido."); return; }
        if (senha.length() < 6) { erro(ex, 400, "A senha deve ter pelo menos 6 caracteres."); return; }
        if (senha.length() > 128) { erro(ex, 400, "Senha muito longa."); return; }
        if (!senha.equals(conf)) { erro(ex, 400, "As senhas não conferem."); return; }

        byte[] sal = new byte[16];
        RND.nextBytes(sal);
        long id;
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO usuarios (nome, email, senha_sal, senha_hash) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nome); ps.setString(2, email);
            ps.setBytes(3, sal); ps.setBytes(4, hash(senha, sal));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getLong(1); }
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) { erro(ex, 409, "Este email já está cadastrado."); return; }  // chave duplicada
            throw e;
        }
        iniciarSessao(ex, id, false);
        Servidor.json(ex, 201, "{\"ok\":true,\"nome\":\"" + Servidor.escaparJson(nome) + "\"}");
    }

    private void login(HttpExchange ex) throws IOException, SQLException {
        Map<String, String> f = Servidor.formulario(ex);
        String email = normalizar(f.get("email"));
        String senha = f.getOrDefault("senha", "");
        boolean manter = "true".equals(f.get("manter"));

        long id = -1; String nome = null; byte[] sal = new byte[16], esperado = null;
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement("SELECT id, nome, senha_sal, senha_hash FROM usuarios WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { id = rs.getLong(1); nome = rs.getString(2); sal = rs.getBytes(3); esperado = rs.getBytes(4); }
            }
        }
        // calcula o hash mesmo se o usuário não existe (não revela quais emails existem pelo tempo de resposta)
        byte[] h = hash(senha.length() > 128 ? "" : senha, sal);
        if (esperado == null || !MessageDigest.isEqual(h, esperado)) { erro(ex, 401, "Email ou senha incorretos."); return; }

        iniciarSessao(ex, id, manter);
        Servidor.json(ex, 200, "{\"ok\":true,\"nome\":\"" + Servidor.escaparJson(nome) + "\"}");
    }

    private void logout(HttpExchange ex) throws IOException, SQLException {
        String t = token(ex);
        if (t != null) {
            try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement("DELETE FROM sessoes WHERE token_hash = ?")) {
                ps.setString(1, sha256(t));
                ps.executeUpdate();
            }
        }
        ex.getResponseHeaders().add("Set-Cookie", "sessao=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        Servidor.json(ex, 200, "{\"ok\":true}");
    }

    private void eu(HttpExchange ex) throws IOException {
        Usuario u = usuarioDaSessao(ex);
        if (u == null) { erro(ex, 401, "não autenticado"); return; }
        Servidor.json(ex, 200, "{\"nome\":\"" + Servidor.escaparJson(u.nome) + "\",\"email\":\"" + Servidor.escaparJson(u.email) + "\"}");
    }

    // ---------- sessão ----------

    private void iniciarSessao(HttpExchange ex, long usuarioId, boolean manter) throws SQLException {
        byte[] r = new byte[32];
        RND.nextBytes(r);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(r);
        long dur = manter ? LONGA : CURTA;
        long agora = System.currentTimeMillis();
        try (Connection c = conectar()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessoes WHERE expira_em < ?")) {
                ps.setLong(1, agora);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sessoes (token_hash, usuario_id, expira_em) VALUES (?,?,?)")) {
                ps.setString(1, sha256(token)); ps.setLong(2, usuarioId); ps.setLong(3, agora + dur * 1000);
                ps.executeUpdate();
            }
        }
        // sem Max-Age = cookie de sessão (some ao fechar o navegador)
        ex.getResponseHeaders().add("Set-Cookie", "sessao=" + token + "; Path=/; HttpOnly; SameSite=Lax"
                + (manter ? "; Max-Age=" + dur : ""));
    }

    private Usuario usuarioDaSessao(HttpExchange ex) {
        String t = token(ex);
        if (t == null) return null;
        try (Connection c = conectar();
             PreparedStatement ps = c.prepareStatement("SELECT u.id, u.nome, u.email FROM sessoes s "
                     + "JOIN usuarios u ON u.id = s.usuario_id WHERE s.token_hash = ? AND s.expira_em > ?")) {
            ps.setString(1, sha256(t)); ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Usuario(rs.getLong(1), rs.getString(2), rs.getString(3)) : null;
            }
        } catch (SQLException e) {
            System.out.println("MySQL: erro ao validar sessão: " + e.getMessage());
            return null;
        }
    }

    private static String token(HttpExchange ex) {
        List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String h : cookies)
            for (String c : h.split(";")) {
                c = c.trim();
                if (c.startsWith("sessao=")) return c.substring(7);
            }
        return null;
    }

    // ---------- utilitários ----------

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static byte[] hash(String senha, byte[] sal) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(senha.toCharArray(), sal, ITERACOES, 256)).getEncoded();
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static String normalizar(String s) { return s == null ? "" : s.trim().toLowerCase(); }

    private static String limpar(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static void erro(HttpExchange ex, int status, String msg) throws IOException {
        Servidor.json(ex, status, "{\"erro\":\"" + Servidor.escaparJson(msg) + "\"}");
    }
}
