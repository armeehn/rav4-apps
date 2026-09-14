package com.ripostelabs.projection.aa;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

/**
 * The embedded material must parse, belong together, and drive a client handshake through
 * {@link Tls}. There is no phone here, so the peer is a server engine built from the same pair
 * that insists on a client certificate: that exercises the one path the phone will.
 */
public final class TlsTest {

    public static void main(String[] args) throws Exception {
        certificate();
        keyMatchesCertificate();
        engineIsClient();
        loopbackHandshakeAndRecords();
        System.out.println(Check.count + " assertions passed");
    }

    private static void certificate() throws Exception {
        X509Certificate cert = Tls.certificate();
        String subject = cert.getSubjectX500Principal().getName();
        String issuer = cert.getIssuerX500Principal().getName();
        Check.that(subject.contains("JVC Kenwood"), "subject: " + subject);
        Check.that(issuer.contains("Google Automotive Link"), "issuer: " + issuer);
        Check.eq("SHA256withRSA", cert.getSigAlgName(), "signature algorithm");
        cert.checkValidity();
        Check.count++;
    }

    private static void keyMatchesCertificate() throws Exception {
        PrivateKey key = Tls.privateKey();
        Check.eq("RSA", key.getAlgorithm(), "RSA key");
        BigInteger n = ((RSAPrivateKey) key).getModulus();
        BigInteger certN = ((RSAPublicKey) Tls.certificate().getPublicKey()).getModulus();
        Check.that(n.equals(certN), "private key modulus matches the certificate");
        Check.eq(2048, n.bitLength(), "2048-bit modulus");
    }

    private static void engineIsClient() throws Exception {
        SSLEngine e = Tls.newEngine(Tls.Role.CLIENT);
        Check.that(e.getUseClientMode(), "head unit engine is the TLS client");
        String[] protocols = e.getEnabledProtocols();
        Check.eq(1, protocols.length, "one protocol");
        Check.eq(Tls.PROTOCOL, protocols[0], "TLSv1.2 only");
    }

    private static void loopbackHandshakeAndRecords() throws Exception {
        Tls client = Tls.client();
        SSLEngine server = Tls.newEngine(Tls.Role.SERVER);
        server.setNeedClientAuth(true);
        server.beginHandshake();

        int packet = server.getSession().getPacketBufferSize();
        ByteBuffer toServer = ByteBuffer.allocate(packet * 4);
        ByteBuffer serverApp = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());

        byte[] flight = client.handshake(null);
        Check.that(flight.length > 0, "ClientHello produced without input");
        Check.eq(0x16, flight[0] & 0xFF, "first record is a handshake record");

        int rounds = 0;
        while (!client.handshakeDone() && rounds++ < 20) {
            toServer.put(flight);
            toServer.flip();
            ByteBuffer toClient = ByteBuffer.allocate(packet * 4);
            pumpServer(server, toServer, serverApp, toClient);
            toServer.compact();
            toClient.flip();
            byte[] reply = new byte[toClient.remaining()];
            toClient.get(reply);
            flight = client.handshake(reply);
        }
        Check.that(client.handshakeDone(), "handshake completed in " + rounds + " rounds");
        Check.that(server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                || server.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED, "server done too");
        Check.that(server.getSession().getPeerCertificates().length == 1, "server saw the head-unit cert");

        // A chunk the size of one AA frame: encrypted by the client, decrypted by the server.
        byte[] plain = new byte[Frame.MAX_PAYLOAD];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i * 7);
        }
        byte[] cipher = client.encrypt(plain);
        Check.that(cipher.length > plain.length, "ciphertext carries record overhead");
        Check.eq(0x17, cipher[0] & 0xFF, "application data record");
        ByteBuffer in = ByteBuffer.wrap(cipher);
        ByteBuffer out = ByteBuffer.allocate(plain.length + packet);
        while (in.hasRemaining()) {
            SSLEngineResult r = server.unwrap(in, out);
            Check.that(r.getStatus() == SSLEngineResult.Status.OK, "server unwrap " + r.getStatus());
        }
        out.flip();
        byte[] got = new byte[out.remaining()];
        out.get(got);
        Check.bytes(plain, got, "server decrypted what the client encrypted");

        // And the other way, which is what every frame from the phone goes through.
        byte[] fromPhone = "service discovery".getBytes();
        ByteBuffer net = ByteBuffer.allocate(packet);
        server.wrap(ByteBuffer.wrap(fromPhone), net);
        net.flip();
        byte[] record = new byte[net.remaining()];
        net.get(record);
        Check.bytes(fromPhone, client.decrypt(record), "client decrypted the server's record");

        // Half a record must wait for the rest rather than fail.
        server.wrap(ByteBuffer.wrap(fromPhone), net.clear());
        net.flip();
        byte[] whole = new byte[net.remaining()];
        net.get(whole);
        byte[] head = new byte[whole.length / 2];
        byte[] tail = new byte[whole.length - head.length];
        System.arraycopy(whole, 0, head, 0, head.length);
        System.arraycopy(whole, head.length, tail, 0, tail.length);
        Check.eq(0, client.decrypt(head).length, "partial record yields nothing yet");
        Check.bytes(fromPhone, client.decrypt(tail), "completed on the second half");
    }

    private static void pumpServer(SSLEngine server, ByteBuffer in, ByteBuffer app, ByteBuffer out) throws Exception {
        while (true) {
            SSLEngineResult.HandshakeStatus hs = server.getHandshakeStatus();
            if (hs == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                Runnable t;
                while ((t = server.getDelegatedTask()) != null) {
                    t.run();
                }
                continue;
            }
            if (hs == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                server.wrap(ByteBuffer.allocate(0), out);
                continue;
            }
            if (hs == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                app.clear();
                SSLEngineResult r = server.unwrap(in, app);
                if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    return;
                }
                continue;
            }
            return;
        }
    }
}
