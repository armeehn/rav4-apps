package com.ripostelabs.projection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Stage 2's byte pipe: the TCP socket the phone opens after the Bluetooth bootstrap. Same
 * {@link Projector.Pipe} contract as {@link UsbLink}, so {@link Projector} and
 * {@link com.ripostelabs.projection.aa.Session} do not know which one they are on.
 *
 * <pre>
 *   ServerSocket :5288  --accept()-->  Socket  --wrap()-->  TcpTransport
 *                                                              read()  <- phone
 *                                                              write() -> phone
 * </pre>
 *
 * <p>The head unit is the server: the phone is told {@code ip:port} in WifiStartRequest and
 * dials it (both wireless references, see {@code aa/Wifi.java}). Reads time out so the reader
 * loop can notice a stop request; a timeout is -1, end of stream is an exception.
 */
final class TcpTransport implements Projector.Pipe {

    private static final int READ_TIMEOUT_MS = 500;
    private static final int BACKLOG = 1;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();

    private TcpTransport(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Bound and listening on every interface, so the soft-AP address needs no lookup here. */
    static ServerSocket listen(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), BACKLOG);
        return server;
    }

    static TcpTransport wrap(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        return new TcpTransport(socket);
    }

    String peer() {
        return String.valueOf(socket.getInetAddress());
    }

    @Override
    public int read(byte[] buf) throws IOException {
        int n;
        try {
            n = in.read(buf);
        } catch (SocketTimeoutException e) {
            return -1;
        }
        if (n < 0) {
            throw new IOException("phone closed the socket");
        }
        return n;
    }

    @Override
    public void write(byte[] data) throws IOException {
        synchronized (writeLock) {
            out.write(data);
            out.flush();
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone; nothing to release twice.
        }
    }
}
