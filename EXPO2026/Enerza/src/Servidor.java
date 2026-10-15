import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.Executors;

/**
 * Servidor da aplicação Enerzā.
 * - Serve os arquivos HTML/CSS/JS/imagens da pasta do projeto
 * - /spotify/*  e /api/spotify/*  -> integração com o Spotify (o token fica só no servidor)
 * - /api/xiaomi/*                  -> controle de aparelhos Xiaomi (Mi Home) pela rede local
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

    private static Path raiz;

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

        SpotifyService spotify = new SpotifyService(cfg, base);
        XiaomiService xiaomi = new XiaomiService(cfg);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", porta), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/spotify/", spotify::tratar);
        server.createContext("/api/spotify/", spotify::tratar);
        server.createContext("/api/xiaomi/", xiaomi::tratar);
        server.createContext("/", Servidor::arquivoEstatico);
        server.start();

        System.out.println("Enerza rodando em " + base + "/");
        if (Desktop.isDesktopSupported() && !"false".equals(cfg.getProperty("abrir.navegador"))) {
            try { Desktop.getDesktop().browse(new URI(base + "/")); } catch (Exception ignored) { }
        }
    }

    private static void arquivoEstatico(HttpExchange ex) throws IOException {
        try {
            String caminho = URLDecoder.decode(ex.getRequestURI().getRawPath(), "UTF-8");
            if (caminho.equals("/")) caminho = "/html/index.html";
            Path arq = raiz.resolve(caminho.substring(1)).normalize();
            String ext = extensao(arq.getFileName() == null ? "" : arq.getFileName().toString());

            // só entrega arquivos dentro da pasta do projeto e de tipos conhecidos (nunca config.properties ou .java)
            if (!arq.startsWith(raiz) || !TIPOS.containsKey(ext) || !Files.isRegularFile(arq)) {
                enviar(ex, 404, "text/plain; charset=utf-8", "Página não encontrada".getBytes(StandardCharsets.UTF_8));
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
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
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
