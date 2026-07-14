package com.agora.platform.snowflake;

/**
 * Snowflake ID: 41-bit ms timestamp (epoch 2026-01-01) | 10-bit machine | 12-bit sequence.
 * Java twin of the Go implementation in services/link-service — same layout so IDs
 * from any service interleave sortably.
 */
public final class Snowflake {

    private static final long EPOCH_MS = 1767225600000L; // 2026-01-01T00:00:00Z
    private static final int MACHINE_BITS = 10;
    private static final int SEQ_BITS = 12;
    private static final long MAX_MACHINE_ID = (1L << MACHINE_BITS) - 1;
    private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;

    private final long machineId;
    private long lastMs = -1;
    private long seq = 0;

    public Snowflake(long machineId) {
        if (machineId < 0 || machineId > MAX_MACHINE_ID) {
            throw new IllegalArgumentException("machine id out of range [0," + MAX_MACHINE_ID + "]: " + machineId);
        }
        this.machineId = machineId;
    }

    public synchronized long next() {
        long now = System.currentTimeMillis();
        // Clock moved backwards: refuse to reuse timestamps, wait it out.
        while (now < lastMs) {
            now = System.currentTimeMillis();
        }
        if (now == lastMs) {
            seq = (seq + 1) & MAX_SEQ;
            if (seq == 0) { // sequence exhausted in this ms
                while (now <= lastMs) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            seq = 0;
        }
        lastMs = now;
        return ((now - EPOCH_MS) << (MACHINE_BITS + SEQ_BITS)) | (machineId << SEQ_BITS) | seq;
    }
}
