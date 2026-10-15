import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integração com o Spotify (Authorization Code Flow).
 * O navegador nunca vê o token: ele chama /api/spotify/* e o Java conversa com a Web API.
 */
class SpotifyService {

    private static final String SCOPE = "user-read-playback-state user-modify-playback-state user-read-currently-playing";
    private static final Path ARQ_TOKEN = Paths.get(".spotify-token");

    private final String clientId, clientSecret, redirectUri;
    private String accessToken, refreshToken, stateEsperado;
    private long expiraEm;

    SpotifyService(Properties cfg, String base) {
        clientId = cfg.getProperty("spotify.client.id", "").trim();
        clientSecret = cfg.getProperty("spotify.client.secret", "").trim();
        redirectUri = base + "/spotify/callback";
        try {
            if (Files.exists(ARQ_TOKEN)) refreshToken = new String(Files.readAllBytes(ARQ_TOKEN), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) { }
        if (clientId.isEmpty()) System.out.println("Spotify: preencha spotify.client.id/secret no config.properties.");
        else System.out.println("Spotify: cadastre este Redirect URI no painel do Spotify -> " + redirectUri);
    }

    void tratar(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath();
            switch (p) {
                case "/spotify/login":    login(ex); break;
                case "/spotify/callback": callback(ex); break;
                case "/spotify/logout":   logout(ex); break;
                case "/api/spotify/player": estado(ex); break;
                case "/api/spotify/play":     comando(ex, "PUT", "/play"); break;
                case "/api/spotify/pause":    comando(ex, "PUT", "/pause"); break;
                case "/api/spotify/next":     comando(ex, "POST", "/next"); break;
                case "/api/spotify/previous": comando(ex, "POST", "/previous"); break;
                default: Servidor.json(ex, 404, "{\"erro\":\"rota inexistente\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            Servidor.json(ex, 502, "{\"erro\":\"" + Servidor.escaparJson(String.valueOf(e.getMessage())) + "\"}");
        } finally {
            ex.close();
        }
    }

    private void login(HttpExchange ex) throws IOException {
        if (clientId.isEmpty()) {
            Servidor.enviar(ex, 500, "text/plain; charset=utf-8",
                    "Configure spotify.client.id e spotify.client.secret no config.properties".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        stateEsperado = Base64.getUrlEncoder().withoutPadding().encodeToString(r);
        Servidor.redirecionar(ex, "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPE)
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(stateEsperado));
    }

    private void callback(HttpExchange ex) throws IOException {
        java.util.Map<String, String> q = Servidor.query(ex);
        if (q.get("code") == null || stateEsperado == null || !stateEsperado.equals(q.get("state"))) {
            Servidor.redirecionar(ex, "/html/inicio.html?spotify=erro");
            return;
        }
        stateEsperado = null;
        boolean ok = pedirToken("grant_type=authorization_code&code=" + enc(q.get("code")) + "&redirect_uri=" + enc(redirectUri));
        Servidor.redirecionar(ex, ok ? "/html/inicio.html" : "/html/inicio.html?spotify=erro");
    }

    private void logout(HttpExchange ex) throws IOException {
        accessToken = refreshToken = null;
        Files.deleteIfExists(ARQ_TOKEN);
        Servidor.redirecionar(ex, "/html/inicio.html");
    }

    /** Devolve o JSON de /v1/me/player ou {"conectado":false} */
    private void estado(HttpExchange ex) throws IOException {
        if (!temToken()) { Servidor.json(ex, 200, "{\"conectado\":false}"); return; }
        Resposta r = chamarApi("GET", "");
        if (r.status == 204) Servidor.json(ex, 200, "{\"conectado\":true,\"tocando\":null}");
        else Servidor.json(ex, r.status, r.corpo.isEmpty() ? "{}" : r.corpo);
    }

    private void comando(HttpExchange ex, String metodo, String path) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { Servidor.json(ex, 405, "{\"erro\":\"use POST\"}"); return; }
        if (!temToken()) { Servidor.json(ex, 401, "{\"conectado\":false}"); return; }
        Resposta r = chamarApi(metodo, path);
        Servidor.json(ex, r.status == 204 ? 200 : r.status, r.corpo.isEmpty() ? "{\"ok\":true}" : r.corpo);
    }

    // ---------- HTTP com o Spotify ----------

    private boolean temToken() throws IOException {
        if (accessToken != null && System.currentTimeMillis() < expiraEm) return true;
        return refreshToken != null && pedirToken("grant_type=refresh_token&refresh_token=" + enc(refreshToken));
    }

    private synchronized boolean pedirToken(String corpo) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)));
        try (OutputStream os = c.getOutputStream()) { os.write(corpo.getBytes(StandardCharsets.UTF_8)); }
        Resposta r = ler(c);
        if (r.status != 200) {
            System.out.println("Spotify: falha ao obter token (" + r.status + "): " + r.corpo);
            if (r.status == 400) { refreshToken = null; Files.deleteIfExists(ARQ_TOKEN); }
            return false;
        }
        accessToken = campo(r.corpo, "access_token");
        String exp = campoNumero(r.corpo, "expires_in");
        expiraEm = System.currentTimeMillis() + (exp == null ? 3600 : Long.parseLong(exp)) * 1000L - 60_000L;
        String novoRefresh = campo(r.corpo, "refresh_token");
        if (novoRefresh != null) {
            refreshToken = novoRefresh;
            Files.write(ARQ_TOKEN, refreshToken.getBytes(StandardCharsets.UTF_8));
        }
        return accessToken != null;
    }

    private Resposta chamarApi(String metodo, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.spotify.com/v1/me/player" + path).openConnection();
        c.setRequestMethod(metodo);
        c.setRequestProperty("Authorization", "Bearer " + accessToken);
        if (!"GET".equals(metodo)) {           // Spotify exige Content-Length em PUT/POST sem corpo
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
        }
        return ler(c);
    }

    private static Resposta ler(HttpURLConnection c) throws IOException {
        int status = c.getResponseCode();
        byte[] b = Servidor.lerTudo(status >= 400 ? c.getErrorStream() : c.getInputStream());
        return new Resposta(status, new String(b, StandardCharsets.UTF_8));
    }

    private static class Resposta {
        final int status; final String corpo;
        Resposta(int s, String c) { status = s; corpo = c; }
    }

    private static String campo(String json, String nome) {
        Matcher m = Pattern.compile("\"" + nome + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String campoNumero(String json, String nome) {
        Matcher m = Pattern.compile("\"" + nome + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (IOException e) { throw new RuntimeException(e); }
    }
}
