package io.mineway.core.tunnel;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * UdpPipe — UDP connection ระหว่าง Tunnel ↔ MC Bedrock (Geyser) server สำหรับผู้เล่น 1 คน
 */
public class UdpPipe implements Pipe {

    private static final int BUFFER_SIZE = 4096;

    private final String  connId;
    private final DatagramSocket socket;
    private final InetSocketAddress targetAddress;
    private final BiConsumer<String, byte[]> onDataFromMc;  // (connId, data)
    private final Consumer<String>           onClose;       // (connId)

    private volatile boolean closed = false;
    private Thread readThread;

    public UdpPipe(
        String  connId,
        DatagramSocket socket,
        int targetPort,
        BiConsumer<String, byte[]> onDataFromMc,
        Consumer<String>           onClose
    ) {
        this.connId = connId;
        this.socket = socket;
        this.targetAddress = new InetSocketAddress("127.0.0.1", targetPort);
        this.onDataFromMc = onDataFromMc;
        this.onClose = onClose;
    }

    @Override
    public void start() {
        readThread = new Thread(() -> {
            byte[] buf = new byte[BUFFER_SIZE];
            try {
                while (!closed && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    
                    int n = packet.getLength();
                    if (n > 0) {
                        byte[] data = new byte[n];
                        System.arraycopy(buf, 0, data, 0, n);
                        onDataFromMc.accept(connId, data);
                    }
                }
            } catch (IOException e) {
                // socket closed or error
            } finally {
                close();
                onClose.accept(connId);
            }
        }, "Mineway-UdpPipe-" + connId);
        readThread.setDaemon(true);
        readThread.start();
    }

    @Override
    public void writeToMc(byte[] data) {
        if (closed || socket.isClosed()) return;
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, targetAddress);
            socket.send(packet);
        } catch (IOException e) {
            close();
            onClose.accept(connId);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
