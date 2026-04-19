package io.mineway.core;

/**
 * Interface ที่ platform adapter แต่ละตัวต้อง implement
 * Core ใช้ interface นี้สื่อสารกลับไปยัง platform
 */
public interface PlatformAdapter {

    /** Log message ระดับ INFO */
    void logInfo(String message);

    /** Log message ระดับ WARNING */
    void logWarn(String message);

    /** Log message ระดับ SEVERE */
    void logError(String message);

    /** Log debug (แสดงเฉพาะตอน debug=true) */
    void logDebug(String message);

    /** รัน task บน main thread (สำหรับ platform ที่ต้องการ) */
    void runOnMainThread(Runnable task);

    /** รัน async task */
    void runAsync(Runnable task);

    /** ชื่อ platform สำหรับ log */
    String getPlatformName();

    /** Version ของ MC server */
    String getServerVersion();

    /** ดึง config ล่าสุด (ใช้ตอน auto-reconnect) */
    default io.mineway.core.config.TunnelConfig getLatestConfig() {
        return null;
    }
}
