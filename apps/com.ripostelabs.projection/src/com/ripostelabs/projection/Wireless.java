package com.ripostelabs.projection;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ripostelabs.projection.aa.Wifi;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Stage 2 end to end: raise the AP, listen on TCP, listen on RFCOMM, walk one phone through
 * the {@link Wifi.Bootstrap}, and hand the socket it then opens to {@link Projector}.
 *
 * <pre>
 *   enable()
 *     |-- TcpTransport.listen(5288) ---- accept thread ----> projector.start(socket)
 *     |-- SoftAp.start() --onUp(ap, ip)--> BtRfcomm.listen()
 *                                              |-- onPhone(bt) --> bootstrap thread:
 *                                                    Wifi.Bootstrap(ap, ip:5288) over bt streams
 *                                                    VERSION -> INFO -> START -> CONNECTED
 * </pre>
 *
 * <p>The TCP server is up before the AP so the port is already open when the phone is told
 * about it. One status line per state change, on the main thread through {@link Projector.Screen}.
 */
final class Wireless implements Closeable {

    private static final String TAG = "Projection";
    private static final int BT_READ_BUFFER = 1024;

    private final Context context;
    private final Projector projector;
    private final Projector.Screen screen;
    private final Handler main = new Handler(Looper.getMainLooper());

    private ServerSocket server;
    private Thread acceptor;
    private SoftAp ap;
    private BtRfcomm rfcomm;
    private Wifi.ApInfo apInfo;
    private String apIp;
    private volatile boolean on;

    Wireless(Context context, Projector projector, Projector.Screen screen) {
        this.context = context;
        this.projector = projector;
        this.screen = screen;
    }

    boolean isOn() {
        return on;
    }

    /** Main thread. Needs the runtime permissions of {@link BtRfcomm} and {@link SoftAp} granted. */
    void enable() {
        if (on) {
            return;
        }
        on = true;
        try {
            server = TcpTransport.listen(Wifi.DEFAULT_PORT);
        } catch (IOException e) {
            disable("tcp listen failed: " + e.getMessage());
            return;
        }
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "projection-tcp-accept");
        acceptor.start();
        status("wireless: listening on tcp " + Wifi.DEFAULT_PORT);

        ap = new SoftAp(context);
        ap.start(new SoftAp.Listener() {
            @Override
            public void onUp(Wifi.ApInfo info, String ip) {
                apInfo = info;
                apIp = ip;
                status("wireless: ap " + info.ssid + " up at " + ip);
                listenRfcomm();
            }

            @Override
            public void onDown(String why) {
                disable("ap: " + why);
            }
        });
    }

    private void listenRfcomm() {
        try {
            rfcomm = BtRfcomm.listen(context, new BtRfcomm.Listener() {
                @Override
                public void onPhone(BluetoothSocket socket) {
                    bootstrap(socket);
                }

                @Override
                public void onLog(String line) {
                    status(line);
                }
            });
        } catch (IOException e) {
            disable("rfcomm: " + e.getMessage());
            return;
        }
        status("wireless: rfcomm service " + Wifi.SERVICE_NAME + " registered, waiting for a phone");
    }

    /** Accept thread: one bootstrap conversation per phone, then keep answering pings. */
    private void bootstrap(BluetoothSocket socket) {
        String who = socket.getRemoteDevice() == null ? "?" : socket.getRemoteDevice().getAddress();
        status("wireless: phone " + who + " on rfcomm");
        try {
            InputStream in = socket.getInputStream();
            final OutputStream out = socket.getOutputStream();
            Wifi.Bootstrap.Config cfg = config();
            Wifi.Bootstrap b = new Wifi.Bootstrap(new Wifi.Bootstrap.Link() {
                @Override
                public void write(byte[] data) throws IOException {
                    out.write(data);
                    out.flush();
                }
            }, new Wifi.Bootstrap.Sink() {
                @Override
                public void log(String line) {
                    status(line);
                }

                @Override
                public void onState(Wifi.Bootstrap.State state) {
                    status("wireless: bootstrap " + state);
                }
            }, cfg);
            b.start();

            byte[] buf = new byte[BT_READ_BUFFER];
            while (on) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                b.onBytes(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            status("wireless: rfcomm ended: " + e.getMessage());
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to do with it.
        }
    }

    private Wifi.Bootstrap.Config config() {
        Wifi.Bootstrap.Config c = new Wifi.Bootstrap.Config();
        c.ip = apIp;
        c.port = Wifi.DEFAULT_PORT;
        c.ap = apInfo;
        c.headUnit.carMake = "Toyota";
        c.headUnit.carModel = "RAV4";
        c.headUnit.carYear = "2019";
        c.headUnit.headUnitMake = "Riposte Laboratories";
        c.headUnit.headUnitModel = "GT6-EAU";
        c.headUnit.softwareBuild = "1";
        c.headUnit.softwareVersion = "stage2";
        return c;
    }

    private void acceptLoop() {
        while (on) {
            Socket phone;
            try {
                phone = server.accept();
            } catch (IOException e) {
                if (on) {
                    status("wireless: tcp accept failed: " + e.getMessage());
                }
                return;
            }
            status("wireless: phone " + phone.getInetAddress() + " on tcp, starting session");
            projector.start(phone);
        }
    }

    /** Any thread. */
    void disable(String why) {
        if (!on) {
            return;
        }
        on = false;
        if (rfcomm != null) {
            rfcomm.close();
            rfcomm = null;
        }
        if (ap != null) {
            ap.close();
            ap = null;
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                Log.w(TAG, "tcp close: " + e.getMessage());
            }
            server = null;
        }
        status("wireless: off (" + why + ")");
    }

    @Override
    public void close() {
        disable("closed");
    }

    private void status(final String line) {
        Log.i(TAG, line);
        main.post(new Runnable() {
            @Override
            public void run() {
                screen.onStatus(line);
            }
        });
    }
}
