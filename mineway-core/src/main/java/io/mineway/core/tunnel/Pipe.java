package io.mineway.core.tunnel;

public interface Pipe {
    void start();
    void writeToMc(byte[] data);
    void close();
    boolean isClosed();
}
