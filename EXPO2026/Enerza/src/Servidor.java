
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Servidor da aplicação Enerzā.
 * - Serve os arquivos HTML/CSS/JS/imagens da pasta do projeto
 * - /api/auth/*                    -> cadastro e login
 * - /spotify/*  e /api/spotify/*  -> integração com o Spotify (exige login)
 * - /api/xiaomi/*                  -> controle de aparelhos Xiaomi (exige login)
 */
public class Servidor {

    private static final Map<String, String> TIPOS = new HashMap<>();
    static {
        TIPOS.put("html", "text/html; charset=utf-8");
        TIPOS.put("css", "text/css; charset=utf-8");
        TIPOS.put("js", "application/javascript; charset=utf-8");
        TIPOS.put("png", "image/png");
        TIPOS.put("jpg", "image/jpeg");
        TIPOS.put("jpeg", "image/jpeg");
        TIPOS.put("gif", "image/gif");
        TIPOS.put("svg", "image/svg+xml");
        TIPOS.put("ico", "image/x-icon");
        TIPOS.put("webp", "image/webp");
    }

    // páginas que podem ser abertas sem estar logado
    private static final Set<String> PAGINAS_PUBLICAS = Set.of("index.html", "cadastro.html", "login2.html");

    private static Path raiz;
    private static AuthService auth;

    public static void main(String[] args) throws Exception {
        Properties cfg = new Properties();
        Path arqCfg = Paths.get("config.properties");
        if (Files.exists(arqCfg)) {
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(arqCfg.toFile()), StandardCharsets.UTF_8)) {
                cfg.load(r);
            }
        } else {
            System.out.println("AVISO: config.properties não encontrado - Spotify e Xiaomi ficarão desativados.");
        }

        int porta = Integer.parseInt(cfg.getProperty("porta", "8080"));
        raiz = Paths.get(cfg.getProperty("pasta.web", ".")).toAbsolutePath().normalize();
        String base = "http://127.0.0.1:" + porta;

        try {
            auth = new AuthService(cfg);
        } catch (Exception e) {
            System.out.println("ERRO MySQL: " + e.getMessage());
            System.exit(1);
        }
        SpotifyService spotify = new SpotifyService(cfg, base);
        XiaomiService xiaomi = new XiaomiService(cfg);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", porta), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/api/auth/", auth::tratar);
        server.createContext("/spotify/", protegido(spotify::tratar));
        server.createContext("/api/spotify/", protegido(spotify::tratar));
        server.createContext("/api/xiaomi/", protegido(xiaomi::tratar));
        server.createContext("/", Servidor::arquivoEstatico);
        server.start();

        System.out.println("Enerza rodando em " + base + "/");
        if (Desktop.isDesktopSupported() && !"false".equals(cfg.getProperty("abrir.navegador"))) {
            try { Desktop.getDesktop().browse(new URI(base + "/")); } catch (Exception ignored) { }
        }
    }

    /** Só deixa passar quem está logado: API responde 401, páginas redirecionam para o login. */
    private static HttpHandler protegido(HttpHandler h) {
        return ex -> {
            if (auth.emailLogado(ex) != null) { h.handle(ex); return; }
            try {
                if (ex.getRequestURI().getPath().startsWith("/api/")) json(ex, 401, "{\"erro\":\"não autenticado\"}");
                else redirecionar(ex, "/html/login2.html");
            } finally {
                ex.close();
            }
        };
    }

    private static void arquivoEstatico(HttpExchange ex) throws IOException {
        try {
            String caminho = URLDecoder.decode(ex.getRequestURI().getRawPath(), "UTF-8");
            if (caminho.equals("/")) caminho = Files.exists(raiz.resolve("html/index.html")) ? "/html/index.html" : "/index.html";
            Path arq = raiz.resolve(caminho.substring(1)).normalize();
            String nome = arq.getFileName() == null ? "" : arq.getFileName().toString();
            String ext = extensao(nome);

            // só entrega arquivos dentro da pasta do projeto e de tipos conhecidos (nunca config.properties ou .java)
            if (!arq.startsWith(raiz) || !TIPOS.containsKey(ext) || !Files.isRegularFile(arq)) {
                enviar(ex, 404, "text/plain; charset=utf-8", "Página não encontrada".getBytes(StandardCharsets.UTF_8));
                return;
            }
            // páginas internas (inicio.html etc.) exigem login
            if (ext.equals("html") && !PAGINAS_PUBLICAS.contains(nome) && auth.emailLogado(ex) == null) {
                redirecionar(ex, "/html/login2.html");
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            enviar(ex, 200, TIPOS.get(ext), Files.readAllBytes(arq));
        } finally {
            ex.close();
        }
    }

    private static String extensao(String nome) {
        int i = nome.lastIndexOf('.');
        return i < 0 ? "" : nome.substring(i + 1).toLowerCase();
    }

    // ---------- utilitários usados pelos serviços ----------

    static void enviar(HttpExchange ex, int status, String tipo, byte[] corpo) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        if (corpo.length == 0 || status == 204) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, corpo.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(corpo); }
    }

    static void json(HttpExchange ex, int status, String json) throws IOException {
        enviar(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static void redirecionar(HttpExchange ex, String url) throws IOException {
        ex.getResponseHeaders().set("Location", url);
        ex.sendResponseHeaders(302, -1);
    }

    static Map<String, String> query(HttpExchange ex) {
        return parse(ex.getRequestURI().getRawQuery());
    }

    /** Lê o corpo de um formulário (application/x-www-form-urlencoded), limitado a 8 KB. */
    static Map<String, String> formulario(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(8192);
        return parse(new String(b, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String par : q.split("&")) {
            int i = par.indexOf('=');
            try {
                if (i > 0) m.put(URLDecoder.decode(par.substring(0, i), "UTF-8"), URLDecoder.decode(par.substring(i + 1), "UTF-8"));
            } catch (IOException ignored) { }
        }
        return m;
    }

    static byte[] lerTudo(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    static String escaparJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
