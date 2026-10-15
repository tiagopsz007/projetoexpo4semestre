import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cliente do protocolo miIO (UDP 54321), usado pelos aparelhos do app Mi Home / Xiaomi Home.
 * Cada aparelho tem um token de 32 caracteres hexadecimais (veja o LEIA-ME para obter).
 *
 * Pacote: cabeçalho de 32 bytes (0x2131, tamanho, 0, deviceId, stamp, md5) + JSON criptografado com AES-128-CBC,
 * onde chave = md5(token) e iv = md5(chave + token).
 */
class MiioClient {

    private static final int PORTA = 54321;
    private static final int TIMEOUT_MS = 2000;

    private final InetAddress ip;
    private final byte[] token, chave, iv;
    private int deviceId = -1, stampDispositivo;
    private long stampLocal;
    private int proximoId = ThreadLocalRandom.current().nextInt(1, 10_000);

    MiioClient(String ip, String tokenHex) throws IOException {
        this.ip = InetAddress.getByName(ip);
        this.token = hex(tokenHex.trim());
        if (token.length != 16) throw new IllegalArgumentException("token Xiaomi deve ter 32 caracteres hexadecimais");
        this.chave = md5(token);
        this.iv = md5(concat(chave, token));
    }

    /** Envia {"id":..,"method":..,"params":..} e devolve o JSON de resposta do aparelho. */
    synchronized String enviar(String metodo, String paramsJson) throws IOException {
        IOException ultimoErro = null;
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(TIMEOUT_MS);
                if (deviceId == -1 || tentativa > 0) handshake(s);

                String msg = "{\"id\":" + (proximoId++) + ",\"method\":\"" + metodo + "\",\"params\":" + paramsJson + "}";
                byte[] dados = cifrar(msg.getBytes(StandardCharsets.UTF_8), Cipher.ENCRYPT_MODE);
                int stamp = stampDispositivo + (int) ((System.currentTimeMillis() - stampLocal) / 1000);

                ByteBuffer pac = ByteBuffer.allocate(32 + dados.length);
                pac.putShort((short) 0x2131).putShort((short) (32 + dados.length)).putInt(0).putInt(deviceId).putInt(stamp);
                pac.put(token).put(dados);                       // token ocupa o lugar do checksum para calcular o md5
                byte[] bytes = pac.array();
                System.arraycopy(md5(bytes), 0, bytes, 16, 16);

                s.send(new DatagramPacket(bytes, bytes.length, ip, PORTA));
                byte[] resp = receber(s);
                if (resp.length <= 32) throw new IOException("resposta vazia do aparelho");

                byte[] claro = cifrar(Arrays.copyOfRange(resp, 32, resp.length), Cipher.DECRYPT_MODE);
                return new String(claro, StandardCharsets.UTF_8).replace("\u0000", "").trim();
            } catch (SocketTimeoutException e) {
                ultimoErro = new IOException("aparelho não respondeu (IP ou token incorretos?)");
            } catch (GeneralSecurityException e) {
                ultimoErro = new IOException("token incorreto (falha ao descriptografar)");
            }
        }
        throw ultimoErro;
    }

    private void handshake(DatagramSocket s) throws IOException {
        byte[] hello = new byte[32];
        Arrays.fill(hello, (byte) 0xFF);
        hello[0] = 0x21; hello[1] = 0x31; hello[2] = 0x00; hello[3] = 0x20;
        s.send(new DatagramPacket(hello, hello.length, ip, PORTA));
        ByteBuffer r = ByteBuffer.wrap(receber(s));
        deviceId = r.getInt(8);
        stampDispositivo = r.getInt(12);
        stampLocal = System.currentTimeMillis();
    }

    private static byte[] receber(DatagramSocket s) throws IOException {
        byte[] buf = new byte[4096];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        s.receive(p);
        return Arrays.copyOf(buf, p.getLength());
    }

    private byte[] cifrar(byte[] dados, int modo) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(modo, new SecretKeySpec(chave, "AES"), new IvParameterSpec(iv));
        return c.doFinal(dados);
    }

    private static byte[] md5(byte[] b) {
        try { return MessageDigest.getInstance("MD5").digest(b); }
        catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return r;
    }
}
