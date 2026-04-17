package io.mineway.core.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * PlayerPipe — TCP connection ระหว่าง Tunnel ↔ MC server สำหรับผู้เล่น 1 คน
 *
 * ทิศทาง:
 *   TunnelClient.handleMessage("player_data") → writeToMc() → MC server
 *   MC server → read loop → sendToTunnel callback → TunnelClient.sendMcData()
 */
public class PlayerPipe implements Pipe {

    private static final int BUFFER_SIZE = 32 * 1024; // 32KB

    private final String  connId;
    private final Socket  socket;
    private final BiConsumer<String, byte[]> onDataFromMc;  // (connId, data)
    private final Consumer<String>           onClose;       // (connId)

    private volatile boolean closed = false;
    private Thread readThread;

    public PlayerPipe(
        String  connId,
        Socket  socket,
        BiConsumer<String, byte[]> onDataFromMc,
        Consumer<String>           onClose
    ) {
        this.connId      = connId;
        this.socket      = socket;
        this.onDataFromMc = onDataFromMc;
        this.onClose     = onClose;
    }

    /** เริ่ม read loop (background thread) */
    public void start() {
        readThread = new Thread(() -> {
            byte[] buf = new byte[BUFFER_SIZE];
            try (InputStream in = socket.getInputStream()) {
                int n;
                while (!closed && (n = in.read(buf)) != -1) {
                    if (n > 0) {
                        byte[] data = new byte[n];
                        System.arraycopy(buf, 0, data, 0, n);
                        onDataFromMc.accept(connId, data);
                    }
                }
            } catch (IOException e) {
                // socket ปิดปกติ หรือ error
            } finally {
                close();
                onClose.accept(connId);
            }
        }, "Mineway-Pipe-" + connId);
        readThread.setDaemon(true);
        readThread.start();
    }

    /** เขียนข้อมูลจาก tunnel เข้า MC server */
    public void writeToMc(byte[] data) {
        if (closed || socket.isClosed()) return;
        try {
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
        } catch (IOException e) {
            close();
            onClose.accept(connId);
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }

    public boolean isClosed() { return closed; }
}
