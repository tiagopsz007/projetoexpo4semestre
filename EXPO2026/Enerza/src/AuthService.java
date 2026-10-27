
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
import java.util.ArrayList;
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
        final long id, familiaId; final String nome, email, cargo;
        Usuario(long id, String nome, String email, long familiaId, String cargo) {
            this.id = id; this.nome = nome; this.email = email; this.familiaId = familiaId; this.cargo = cargo;
        }
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
            s.executeUpdate("CREATE TABLE IF NOT EXISTS familias ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "nome VARCHAR(80) NOT NULL,"
                    + "criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS usuarios ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "nome VARCHAR(60) NOT NULL,"
                    + "email VARCHAR(255) NOT NULL UNIQUE,"
                    + "senha_sal VARBINARY(16) NOT NULL,"
                    + "senha_hash VARBINARY(32) NOT NULL,"
                    + "criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "familia_id BIGINT NULL,"
                    + "familia_origem_id BIGINT NULL,"
                    + "cargo VARCHAR(10) NOT NULL DEFAULT 'admin',"
                    + "FOREIGN KEY (familia_id) REFERENCES familias(id),"
                    + "FOREIGN KEY (familia_origem_id) REFERENCES familias(id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sessoes ("
                    + "token_hash CHAR(64) PRIMARY KEY,"
                    + "usuario_id BIGINT NOT NULL,"
                    + "expira_em BIGINT NOT NULL,"
                    + "FOREIGN KEY (usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("DELETE FROM sessoes WHERE expira_em < " + System.currentTimeMillis());
            migrarArquivoAntigo(c);
            migrarFamilias(c);
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM usuarios")) {
                rs.next();
                System.out.println("MySQL: banco '" + dbNome + "' pronto, " + rs.getLong(1) + " usuário(s).");
            }
        }
    }

    /** Bancos criados antes das famílias: adiciona as colunas e cria uma família (com o usuário como admin) para cada conta. */
    private void migrarFamilias(Connection c) throws SQLException {
        boolean existe;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'usuarios' AND COLUMN_NAME = 'familia_id'")) {
            ps.setString(1, dbNome);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); existe = rs.getLong(1) > 0; }
        }
        if (!existe) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE usuarios ADD COLUMN familia_id BIGINT NULL, "
                        + "ADD COLUMN cargo VARCHAR(10) NOT NULL DEFAULT 'admin', "
                        + "ADD FOREIGN KEY (familia_id) REFERENCES familias(id)");
            }
        }
        List<Object[]> semFamilia = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, nome FROM usuarios WHERE familia_id IS NULL")) {
            while (rs.next()) semFamilia.add(new Object[]{rs.getLong(1), rs.getString(2)});
        }
        for (Object[] u : semFamilia) {
            long fam = criarFamilia(c, (String) u[1]);
            try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET familia_id = ?, cargo = 'admin' WHERE id = ?")) {
                ps.setLong(1, fam); ps.setLong(2, (Long) u[0]);
                ps.executeUpdate();
            }
        }
        boolean temOrigem;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'usuarios' AND COLUMN_NAME = 'familia_origem_id'")) {
            ps.setString(1, dbNome);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); temOrigem = rs.getLong(1) > 0; }
        }
        if (!temOrigem) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE usuarios ADD COLUMN familia_origem_id BIGINT NULL, "
                        + "ADD FOREIGN KEY (familia_origem_id) REFERENCES familias(id)");
                // admins: a família atual é a de origem; quem já tinha sido movido ganha uma família de origem própria
                st.executeUpdate("UPDATE usuarios SET familia_origem_id = familia_id WHERE cargo = 'admin'");
            }
            List<Long> semOrigem = new ArrayList<>();
            List<String> nomes = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT id, nome FROM usuarios WHERE familia_origem_id IS NULL")) {
                while (rs.next()) { semOrigem.add(rs.getLong(1)); nomes.add(rs.getString(2)); }
            }
            for (int i = 0; i < semOrigem.size(); i++) {
                long fam = criarFamilia(c, nomes.get(i));
                try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET familia_origem_id = ? WHERE id = ?")) {
                    ps.setLong(1, fam); ps.setLong(2, semOrigem.get(i));
                    ps.executeUpdate();
                }
            }
        }
        if (!semFamilia.isEmpty()) System.out.println("MySQL: " + semFamilia.size() + " família(s) criada(s) para contas existentes.");
    }

    /** True se alguém tem esta família como a criada no próprio cadastro (ela não pode ser apagada). */
    private boolean ehOrigem(Connection c, long familiaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM usuarios WHERE familia_origem_id = ?")) {
            ps.setLong(1, familiaId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1) > 0; }
        }
    }

    private long criarFamilia(Connection c, String nomeUsuario) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO familias (nome) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "Família de " + nomeUsuario);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
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
            else if (p.equals("/api/auth/senha") && post) alterarSenha(ex);
            else if (p.equals("/api/auth/familia")) familia(ex);
            else if (p.equals("/api/auth/familia/adicionar") && post) adicionarMembro(ex);
            else if (p.equals("/api/auth/familia/remover") && post) removerMembro(ex);
            else if (p.equals("/api/auth/familia/cargo") && post) alterarCargo(ex);
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
        try (Connection c = conectar()) {
            c.setAutoCommit(false);
            try {
                long familiaId = criarFamilia(c, nome);   // todo cadastro nasce com uma família nova, da qual a pessoa é admin
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO usuarios (nome, email, senha_sal, senha_hash, familia_id, familia_origem_id, cargo) VALUES (?,?,?,?,?,?,'admin')",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, nome); ps.setString(2, email);
                    ps.setBytes(3, sal); ps.setBytes(4, hash(senha, sal));
                    ps.setLong(5, familiaId); ps.setLong(6, familiaId);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getLong(1); }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                if (e.getErrorCode() == 1062) { erro(ex, 409, "Este email já está cadastrado."); return; }  // chave duplicada
                throw e;
            }
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

    private void eu(HttpExchange ex) throws IOException, SQLException {
        Usuario u = usuarioDaSessao(ex);
        if (u == null) { erro(ex, 401, "não autenticado"); return; }
        String nomeFam = "";
        try (Connection c = conectar(); PreparedStatement ps = c.prepareStatement("SELECT nome FROM familias WHERE id = ?")) {
            ps.setLong(1, u.familiaId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) nomeFam = rs.getString(1); }
        }
        Servidor.json(ex, 200, "{\"nome\":\"" + Servidor.escaparJson(u.nome) + "\",\"email\":\"" + Servidor.escaparJson(u.email)
                + "\",\"cargo\":\"" + Servidor.escaparJson(u.cargo) + "\",\"familia\":\"" + Servidor.escaparJson(nomeFam) + "\"}");
    }

    /**
     * POST /api/auth/senha (atual, nova, confirmar) - troca a senha de quem está logado.
     * Exige a senha atual e encerra as outras sessões da conta (a sessão atual continua).
     */
    private void alterarSenha(HttpExchange ex) throws IOException, SQLException {
        Usuario u = usuarioDaSessao(ex);
        if (u == null) { erro(ex, 401, "não autenticado"); return; }
        Map<String, String> f = Servidor.formulario(ex);
        String atual = f.getOrDefault("atual", ""), nova = f.getOrDefault("nova", ""), conf = f.getOrDefault("confirmar", "");

        if (nova.length() < 6) { erro(ex, 400, "A nova senha deve ter pelo menos 6 caracteres."); return; }
        if (nova.length() > 128) { erro(ex, 400, "Senha muito longa."); return; }
        if (!nova.equals(conf)) { erro(ex, 400, "As senhas não conferem."); return; }

        try (Connection c = conectar()) {
            byte[] salAtual = null, esperado = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT senha_sal, senha_hash FROM usuarios WHERE id = ?")) {
                ps.setLong(1, u.id);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { salAtual = rs.getBytes(1); esperado = rs.getBytes(2); } }
            }
            if (esperado == null || atual.length() > 128 || !MessageDigest.isEqual(hash(atual, salAtual), esperado)) {
                erro(ex, 400, "Senha atual incorreta."); return;   // 400 (e não 401) para a página não achar que a sessão caiu
            }
            if (atual.equals(nova)) { erro(ex, 400, "A nova senha deve ser diferente da atual."); return; }

            byte[] sal = new byte[16];
            RND.nextBytes(sal);
            try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET senha_sal = ?, senha_hash = ? WHERE id = ?")) {
                ps.setBytes(1, sal); ps.setBytes(2, hash(nova, sal)); ps.setLong(3, u.id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessoes WHERE usuario_id = ? AND token_hash <> ?")) {
                ps.setLong(1, u.id); ps.setString(2, sha256(token(ex)));
                ps.executeUpdate();
            }
        }
        Servidor.json(ex, 200, "{\"ok\":true}");
    }

    // ---------- família ----------

    /** GET /api/auth/familia -> membros da família do usuário logado. */
    private void familia(HttpExchange ex) throws IOException, SQLException {
        Usuario u = usuarioDaSessao(ex);
        if (u == null) { erro(ex, 401, "não autenticado"); return; }
        String nomeFam = "";
        StringBuilder membros = new StringBuilder();
        try (Connection c = conectar()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT nome FROM familias WHERE id = ?")) {
                ps.setLong(1, u.familiaId);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) nomeFam = rs.getString(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, nome, email, cargo FROM usuarios WHERE familia_id = ? ORDER BY (cargo = 'admin') DESC, id")) {
                ps.setLong(1, u.familiaId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (membros.length() > 0) membros.append(',');
                        membros.append("{\"nome\":\"").append(Servidor.escaparJson(rs.getString(2)))
                                .append("\",\"email\":\"").append(Servidor.escaparJson(rs.getString(3)))
                                .append("\",\"cargo\":\"").append(Servidor.escaparJson(rs.getString(4)))
                                .append("\",\"eu\":").append(rs.getLong(1) == u.id).append('}');
                    }
                }
            }
        }
        Servidor.json(ex, 200, "{\"familia\":\"" + Servidor.escaparJson(nomeFam) + "\",\"souAdmin\":" + "admin".equals(u.cargo)
                + ",\"membros\":[" + membros + "]}");
    }

    /**
     * POST /api/auth/familia/adicionar (email) - só admin.
     * Move a pessoa para a família do admin como "usuario" (ela perde o cargo de admin).
     * A família antiga é apagada se ficar vazia; se sobrar gente sem admin, o membro mais antigo vira admin.
     */
    private void adicionarMembro(HttpExchange ex) throws IOException, SQLException {
        Usuario eu = usuarioDaSessao(ex);
        if (eu == null) { erro(ex, 401, "não autenticado"); return; }
        if (!"admin".equals(eu.cargo)) { erro(ex, 403, "Só o administrador pode adicionar membros."); return; }
        String email = normalizar(Servidor.formulario(ex).get("email"));
        if (email.isEmpty()) { erro(ex, 400, "Informe o email da pessoa."); return; }

        int status = 200; String msg = null, nomeAlvo = "";
        try (Connection c = conectar()) {
            c.setAutoCommit(false);
            try {
                long alvoId = -1, famAntiga = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT id, nome, familia_id FROM usuarios WHERE email = ? FOR UPDATE")) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { alvoId = rs.getLong(1); nomeAlvo = rs.getString(2); famAntiga = rs.getLong(3); }
                    }
                }
                if (alvoId == -1) { status = 404; msg = "Nenhum usuário cadastrado com esse email."; }
                else if (alvoId == eu.id || famAntiga == eu.familiaId) { status = 409; msg = "Essa pessoa já está na sua família."; }
                else {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET familia_id = ?, cargo = 'usuario' WHERE id = ?")) {
                        ps.setLong(1, eu.familiaId); ps.setLong(2, alvoId);
                        ps.executeUpdate();
                    }
                    long restantes, admins;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT COUNT(*), COALESCE(SUM(cargo = 'admin'), 0) FROM usuarios WHERE familia_id = ?")) {
                        ps.setLong(1, famAntiga);
                        try (ResultSet rs = ps.executeQuery()) { rs.next(); restantes = rs.getLong(1); admins = rs.getLong(2); }
                    }
                    if (restantes == 0 && !ehOrigem(c, famAntiga)) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM familias WHERE id = ?")) {
                            ps.setLong(1, famAntiga);
                            ps.executeUpdate();
                        }
                    } else if (restantes > 0 && admins == 0) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE usuarios SET cargo = 'admin' WHERE id = (SELECT id FROM (SELECT MIN(id) AS id FROM usuarios WHERE familia_id = ?) t)")) {
                            ps.setLong(1, famAntiga);
                            ps.executeUpdate();
                        }
                    }
                }
                if (msg == null) c.commit(); else c.rollback();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        if (msg != null) { erro(ex, status, msg); return; }
        Servidor.json(ex, 200, "{\"ok\":true,\"nome\":\"" + Servidor.escaparJson(nomeAlvo) + "\"}");
    }

    /**
     * POST /api/auth/familia/remover (email) - só admin.
     * A pessoa removida volta para a família criada no cadastro dela (admin de novo);
     * só ganha uma família nova se aquela não existir mais ou for a própria família atual.
     */
    private void removerMembro(HttpExchange ex) throws IOException, SQLException {
        Usuario eu = usuarioDaSessao(ex);
        if (eu == null) { erro(ex, 401, "não autenticado"); return; }
        if (!"admin".equals(eu.cargo)) { erro(ex, 403, "Só o administrador pode remover membros."); return; }
        String email = normalizar(Servidor.formulario(ex).get("email"));
        if (email.isEmpty()) { erro(ex, 400, "Informe o email da pessoa."); return; }
        if (email.equals(eu.email)) { erro(ex, 400, "Você não pode remover a si mesmo da família."); return; }

        int status = 200; String msg = null, nomeAlvo = "";
        try (Connection c = conectar()) {
            c.setAutoCommit(false);
            try {
                long alvoId = -1, famAlvo = 0, origem = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT id, nome, familia_id, familia_origem_id FROM usuarios WHERE email = ? FOR UPDATE")) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { alvoId = rs.getLong(1); nomeAlvo = rs.getString(2); famAlvo = rs.getLong(3); origem = rs.getLong(4); }
                    }
                }
                if (alvoId == -1 || famAlvo != eu.familiaId) { status = 404; msg = "Essa pessoa não faz parte da sua família."; }
                else {
                    // volta para a família criada no cadastro; se ela não existir ou for a atual, cria uma nova
                    long destino = origem;
                    if (destino == 0 || destino == eu.familiaId) destino = criarFamilia(c, nomeAlvo);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE usuarios SET familia_id = ?, familia_origem_id = ?, cargo = 'admin' WHERE id = ?")) {
                        ps.setLong(1, destino); ps.setLong(2, destino); ps.setLong(3, alvoId);
                        ps.executeUpdate();
                    }
                }
                if (msg == null) c.commit(); else c.rollback();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        if (msg != null) { erro(ex, status, msg); return; }
        Servidor.json(ex, 200, "{\"ok\":true,\"nome\":\"" + Servidor.escaparJson(nomeAlvo) + "\"}");
    }

    /**
     * POST /api/auth/familia/cargo (email, cargo=admin|usuario) - só admin.
     * Promove um membro a admin ou rebaixa outro admin a usuário comum.
     * O admin não altera o próprio cargo, assim a família nunca fica sem administrador.
     */
    private void alterarCargo(HttpExchange ex) throws IOException, SQLException {
        Usuario eu = usuarioDaSessao(ex);
        if (eu == null) { erro(ex, 401, "não autenticado"); return; }
        if (!"admin".equals(eu.cargo)) { erro(ex, 403, "Só o administrador pode alterar cargos."); return; }
        Map<String, String> f = Servidor.formulario(ex);
        String email = normalizar(f.get("email"));
        String cargo = f.getOrDefault("cargo", "");
        if (!cargo.equals("admin") && !cargo.equals("usuario")) { erro(ex, 400, "Cargo inválido."); return; }
        if (email.equals(eu.email)) { erro(ex, 400, "Você não pode alterar o seu próprio cargo."); return; }

        String nomeAlvo;
        try (Connection c = conectar()) {
            String atual = null; nomeAlvo = "";
            try (PreparedStatement ps = c.prepareStatement("SELECT nome, cargo FROM usuarios WHERE email = ? AND familia_id = ?")) {
                ps.setString(1, email); ps.setLong(2, eu.familiaId);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { nomeAlvo = rs.getString(1); atual = rs.getString(2); } }
            }
            if (atual == null) { erro(ex, 404, "Essa pessoa não faz parte da sua família."); return; }
            if (atual.equals(cargo)) { erro(ex, 409, "Essa pessoa já tem esse cargo."); return; }
            try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET cargo = ? WHERE email = ? AND familia_id = ?")) {
                ps.setString(1, cargo); ps.setString(2, email); ps.setLong(3, eu.familiaId);
                ps.executeUpdate();
            }
        }
        Servidor.json(ex, 200, "{\"ok\":true,\"nome\":\"" + Servidor.escaparJson(nomeAlvo) + "\"}");
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
             PreparedStatement ps = c.prepareStatement("SELECT u.id, u.nome, u.email, u.familia_id, u.cargo FROM sessoes s "
                     + "JOIN usuarios u ON u.id = s.usuario_id WHERE s.token_hash = ? AND s.expira_em > ?")) {
            ps.setString(1, sha256(t)); ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Usuario(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)) : null;
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
