package com.ripostelabs.projection.aa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPrivateCrtKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The TLS leg of the Android Auto handshake, on {@link SSLEngine} so no socket is involved:
 * the ciphertext travels inside SSL_HANDSHAKE control messages, then as ENCRYPTED frames.
 *
 * <p><b>The head unit is the TLS client.</b> Both references say so explicitly: aasdk
 * {@code SSLWrapper::setConnectState} calls {@code SSL_set_connect_state} with
 * {@code TLS_client_method}, and headunit {@code hu_ssl.c} does the same with
 * {@code TLSv1_2_client_method}. The phone is the server; it asks for a client certificate and
 * that is where the material below comes in.
 *
 * <p>The certificate and key are the public test pair every open receiver ships (aasdk
 * {@code Messenger/Cryptor.cpp}, headunit {@code hu_ssl.h}): a "JVC Kenwood" leaf issued by
 * "Google Automotive Link", valid 2014-2045. It is not a secret and it is not ours; it is the
 * identity the phone has accepted from those projects for years. Neither reference verifies the
 * phone's certificate ({@code SSL_VERIFY_NONE}), and neither carries a Google CA to verify it
 * against, so this class trusts the peer the same way and ships no CA.
 */
public final class Tls {

    /** Only version the references negotiate; pinned so the phone sees a familiar ClientHello. */
    public static final String PROTOCOL = "TLSv1.2";

    public static final String CERT_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIDKjCCAhICARswDQYJKoZIhvcNAQELBQAwWzELMAkGA1UEBhMCVVMxEzARBgNV\n"
            + "BAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoM\n"
            + "Fkdvb2dsZSBBdXRvbW90aXZlIExpbmswJhcRMTQwNzA0MDAwMDAwLTA3MDAXETQ1\n"
            + "MDQyOTE0MjgzOC0wNzAwMFMxCzAJBgNVBAYTAkpQMQ4wDAYDVQQIDAVUb2t5bzER\n"
            + "MA8GA1UEBwwISGFjaGlvamkxFDASBgNVBAoMC0pWQyBLZW53b29kMQswCQYDVQQL\n"
            + "DAIwMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAM911mNnUfx+WJtx\n"
            + "uk06GO7kXRW/gXUVNQBkbAFZmVdVNvLoEQNthi2X8WCOwX6n6oMPxU2MGJnvicP3\n"
            + "6kBqfHhfQ2Fvqlf7YjjhgBHh0lqKShVPxIvdatBjVQ76aym5H3GpkigLGkmeyiVo\n"
            + "VO8oc3cJ1bO96wFRmk7kJbYcEjQyakODPDu4QgWUTwp1Z8Dn41ARMG5OFh6otITL\n"
            + "XBzj9REkUPkxfS03dBXGr5/LIqvSsnxib1hJ47xnYJXROUsBy3e6T+fYZEEzZa7y\n"
            + "7tFioHIQ8G/TziPmvFzmQpaWMGiYfoIgX8WoR3GD1diYW+wBaZTW+4SFUZJmRKgq\n"
            + "TbMNFkMCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAsGdH5VFn78WsBElMXaMziqFC\n"
            + "zmilkvr85/QpGCIztI0FdF6xyMBJk/gYs2thwvF+tCCpXoO8mjgJuvJZlwr6fHzK\n"
            + "Ox5hNUb06AeMtsUzUfFjSZXKrSR+XmclVd+Z6/ie33VhGePOPTKYmJ/PPfTT9wvT\n"
            + "93qswcxhA+oX5yqLbU3uDPF1ZnJaEeD/YN45K/4eEA4/0SDXaWW14OScdS2LV0Bc\n"
            + "YmsbkPVNYZn37FlY7e2Z4FUphh0A7yME2Eh/e57QxWrJ1wubdzGnX8mrABc67ADU\n"
            + "U5r9tlTRqMs7FGOk6QS2Cxp4pqeVQsrPts4OEwyPUyb3LfFNo3+sP111D9zEow==\n"
            + "-----END CERTIFICATE-----\n";

    public static final String KEY_PEM = "-----BEGIN RSA PRIVATE KEY-----\n"
            + "MIIEowIBAAKCAQEAz3XWY2dR/H5Ym3G6TToY7uRdFb+BdRU1AGRsAVmZV1U28ugR\n"
            + "A22GLZfxYI7Bfqfqgw/FTYwYme+Jw/fqQGp8eF9DYW+qV/tiOOGAEeHSWopKFU/E\n"
            + "i91q0GNVDvprKbkfcamSKAsaSZ7KJWhU7yhzdwnVs73rAVGaTuQlthwSNDJqQ4M8\n"
            + "O7hCBZRPCnVnwOfjUBEwbk4WHqi0hMtcHOP1ESRQ+TF9LTd0Fcavn8siq9KyfGJv\n"
            + "WEnjvGdgldE5SwHLd7pP59hkQTNlrvLu0WKgchDwb9POI+a8XOZClpYwaJh+giBf\n"
            + "xahHcYPV2Jhb7AFplNb7hIVRkmZEqCpNsw0WQwIDAQABAoIBAB2u7ZLheKCY71Km\n"
            + "bhKYqnKb6BmxgfNfqmq4858p07/kKG2O+Mg1xooFgHrhUhwuKGbCPee/kNGNrXeF\n"
            + "pFW9JrwOXVS2pnfaNw6ObUWhuvhLaxgrhqLAdoUEgWoYOHcKzs3zhj8Gf6di+edq\n"
            + "SyTA8+xnUtVZ6iMRKvP4vtCUqaIgBnXdmQbGINP+/4Qhb5R7XzMt/xPe6uMyAIyC\n"
            + "y5Fm9HnvekaepaeFEf3bh4NV1iN/R8px6cFc6ELYxIZc/4Xbm91WGqSdB0iSriaZ\n"
            + "TjgrmaFjSO40tkCaxI9N6DGzJpmpnMn07ifhl2VjnGOYwtyuh6MKEnyLqTrTg9x0\n"
            + "i3mMwskCgYEA9IyljPRerXxHUAJt+cKOayuXyNt80q9PIcGbyRNvn7qIY6tr5ut+\n"
            + "ZbaFgfgHdSJ/4nICRq02HpeDJ8oj9BmhTAhcX6c1irH5ICjRlt40qbPwemIcpybt\n"
            + "mb+DoNYbI8O4dUNGH9IPfGK8dRpOok2m+ftfk94GmykWbZF5CnOKIp8CgYEA2Syc\n"
            + "5xlKB5Qk2ZkwXIzxbzozSfunHhWWdg4lAbyInwa6Y5GB35UNdNWI8TAKZsN2fKvX\n"
            + "RFgCjbPreUbREJaM3oZ92o5X4nFxgjvAE1tyRqcPVbdKbYZgtcqqJX06sW/g3r/3\n"
            + "RH0XPj2SgJIHew9sMzjGWDViMHXLmntI8rVA7d0CgYBOr36JFwvrqERN0ypNpbMr\n"
            + "epBRGYZVSAEfLGuSzEUrUNqXr019tKIr2gmlIwhLQTmCxApFcXArcbbKs7jTzvde\n"
            + "PoZyZJvOr6soFNozP/YT8Ijc5/quMdFbmgqhUqLS5CPS3z2N+YnwDNj0mO1aPcAP\n"
            + "STmcm2DmxdaolJksqrZ0owKBgQCD0KJDWoQmaXKcaHCEHEAGhMrQot/iULQMX7Vy\n"
            + "gl5iN5E2EgFEFZIfUeRWkBQgH49xSFPWdZzHKWdJKwSGDvrdrcABwdfx520/4MhK\n"
            + "d3y7CXczTZbtN1zHuoTfUE0pmYBhcx7AATT0YCblxrynosrHpDQvIefBBh5YW3AB\n"
            + "cKZCOQKBgEM/ixzI/OVSZ0Py2g+XV8+uGQyC5XjQ6cxkVTX3Gs0ZXbemgUOnX8co\n"
            + "eCXS4VrhEf4/HYMWP7GB5MFUOEVtlLiLM05ruUL7CrphdfgayDXVcTPfk75lLhmu\n"
            + "KAwp3tIHPoJOQiKNQ3/qks5km/9dujUGU2ARiU3qmxLMdgegFz8e\n"
            + "-----END RSA PRIVATE KEY-----\n";

    private static final String ALIAS = "headunit";
    private static final int DER_SEQUENCE = 0x30;
    private static final int DER_INTEGER = 0x02;
    private static final int PKCS1_INTEGERS = 9;

    // ---- key material ------------------------------------------------------------------------

    public static X509Certificate certificate() throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(CERT_PEM.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * The key is PKCS#1 ("RSA PRIVATE KEY"), which {@link KeyFactory} does not read directly.
     * PKCS#1 is one DER SEQUENCE of nine INTEGERs: version, n, e, d, p, q, dP, dQ, qInv.
     */
    public static PrivateKey privateKey() throws GeneralSecurityException {
        byte[] der = base64(pemBody(KEY_PEM));
        BigInteger[] ints = pkcs1Integers(der);
        RSAPrivateCrtKeySpec spec = new RSAPrivateCrtKeySpec(
                ints[1], ints[2], ints[3], ints[4], ints[5], ints[6], ints[7], ints[8]);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static String pemBody(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            if (!line.startsWith("-----")) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** java.util.Base64 only exists from API 26 and the suite's minSdk is 24. */
    private static byte[] base64(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            int v = B64.indexOf(text.charAt(i));
            if (v < 0) {
                continue;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >>> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static BigInteger[] pkcs1Integers(byte[] der) throws GeneralSecurityException {
        int[] pos = {0};
        if (readTag(der, pos) != DER_SEQUENCE) {
            throw new GeneralSecurityException("PKCS#1 key does not start with a SEQUENCE");
        }
        readLength(der, pos);

        BigInteger[] ints = new BigInteger[PKCS1_INTEGERS];
        for (int i = 0; i < PKCS1_INTEGERS; i++) {
            if (readTag(der, pos) != DER_INTEGER) {
                throw new GeneralSecurityException("PKCS#1 integer " + i + " missing");
            }
            int len = readLength(der, pos);
            byte[] v = new byte[len];
            System.arraycopy(der, pos[0], v, 0, len);
            pos[0] += len;
            ints[i] = new BigInteger(v);
        }
        return ints;
    }

    private static int readTag(byte[] der, int[] pos) {
        return der[pos[0]++] & 0xFF;
    }

    /** DER length: one byte below 0x80, else 0x8N followed by N big-endian bytes. */
    private static int readLength(byte[] der, int[] pos) {
        int first = der[pos[0]++] & 0xFF;
        if (first < 0x80) {
            return first;
        }
        int n = first & 0x7F;
        int len = 0;
        for (int i = 0; i < n; i++) {
            len = (len << 8) | (der[pos[0]++] & 0xFF);
        }
        return len;
    }

    // ---- engine ------------------------------------------------------------------------------

    /**
     * Always offers the head-unit cert, whatever CA list the phone puts in CertificateRequest.
     * Extends the engine-aware base: a plain X509KeyManager is never consulted by SSLEngine on
     * JDK 17, which showed up as "no cipher suites in common" in the loopback test.
     */
    private static final class FixedKeyManager extends X509ExtendedKeyManager {
        private static final String KEY_TYPE = "RSA";
        private final X509Certificate cert;
        private final PrivateKey key;

        FixedKeyManager(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }

        private static String forRsa(String keyType) {
            return KEY_TYPE.equals(keyType) ? ALIAS : null;
        }

        private static String forRsa(String[] keyTypes) {
            for (String t : keyTypes) {
                if (KEY_TYPE.equals(t)) {
                    return ALIAS;
                }
            }
            return null;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return forRsa(keyType);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return forRsa(keyType);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return forRsa(keyType);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return forRsa(keyType);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[] {cert};
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {ALIAS};
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[] {ALIAS};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return key;
        }
    }

    /** SSL_VERIFY_NONE, as both references do. */
    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** Which end of the handshake an engine plays. The head unit is always {@link #CLIENT}. */
    public enum Role {
        CLIENT,
        SERVER
    }

    public static SSLEngine newEngine(Role role) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance(PROTOCOL);
        ctx.init(new KeyManager[] {new FixedKeyManager(certificate(), privateKey())},
                new TrustManager[] {new TrustAll()}, null);

        SSLEngine engine = ctx.createSSLEngine();
        engine.setUseClientMode(role == Role.CLIENT);
        engine.setEnabledProtocols(new String[] {PROTOCOL});
        return engine;
    }

    // ---- record pump -------------------------------------------------------------------------

    private final SSLEngine engine;
    private ByteBuffer inNet;
    private final ByteBuffer inApp;
    private final ByteBuffer outNet;

    public Tls(SSLEngine engine) throws SSLException {
        this.engine = engine;
        int packet = engine.getSession().getPacketBufferSize();
        int app = engine.getSession().getApplicationBufferSize();
        inNet = ByteBuffer.allocate(packet * 2);
        inApp = ByteBuffer.allocate(app * 2);
        outNet = ByteBuffer.allocate(packet * 2);
        engine.beginHandshake();
    }

    /** Head-unit side with the embedded identity. */
    public static Tls client() throws GeneralSecurityException, SSLException {
        return new Tls(newEngine(Role.CLIENT));
    }

    public boolean handshakeDone() {
        return engine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    /**
     * Feeds handshake bytes from the peer (null on the first call) and returns the bytes to send
     * back, empty when the engine is waiting for more. Call until {@link #handshakeDone()}.
     */
    public byte[] handshake(byte[] inbound) throws SSLException {
        if (inbound != null) {
            stash(inbound);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (true) {
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                runTasks();
                continue;
            }

            if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                outNet.clear();
                engine.wrap(ByteBuffer.allocate(0), outNet);
                outNet.flip();
                out.write(outNet.array(), outNet.position(), outNet.remaining());
                continue;
            }

            if (hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                inNet.flip();
                inApp.clear();
                SSLEngineResult r = engine.unwrap(inNet, inApp);
                inNet.compact();
                if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    break;
                }
                continue;
            }

            // FINISHED or NOT_HANDSHAKING: nothing more to do until application data flows.
            break;
        }
        return out.toByteArray();
    }

    /** One plaintext chunk in, its TLS record(s) out. Frames are encrypted one at a time. */
    public byte[] encrypt(byte[] plain) throws SSLException {
        ByteBuffer src = ByteBuffer.wrap(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (src.hasRemaining()) {
            outNet.clear();
            SSLEngineResult r = engine.wrap(src, outNet);
            if (r.getStatus() != SSLEngineResult.Status.OK) {
                throw new SSLException("wrap: " + r.getStatus());
            }
            outNet.flip();
            out.write(outNet.array(), outNet.position(), outNet.remaining());
        }
        return out.toByteArray();
    }

    /** The plaintext of one frame's ciphertext. */
    public byte[] decrypt(byte[] cipher) throws SSLException {
        stash(cipher);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (true) {
            inNet.flip();
            if (!inNet.hasRemaining()) {
                inNet.compact();
                break;
            }
            inApp.clear();
            SSLEngineResult r = engine.unwrap(inNet, inApp);
            inNet.compact();

            if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                break;
            }
            if (r.getStatus() != SSLEngineResult.Status.OK) {
                throw new SSLException("unwrap: " + r.getStatus());
            }
            inApp.flip();
            out.write(inApp.array(), inApp.position(), inApp.remaining());
        }
        return out.toByteArray();
    }

    private void stash(byte[] bytes) {
        if (inNet.remaining() < bytes.length) {
            ByteBuffer bigger = ByteBuffer.allocate(inNet.position() + bytes.length);
            inNet.flip();
            bigger.put(inNet);
            inNet = bigger;
        }
        inNet.put(bytes);
    }

    private void runTasks() {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            task.run();
        }
    }
}
