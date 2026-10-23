package org.figuramc.figuralink.util;

import org.figuramc.figuralink.FiguraLink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class AvatarTransferTracker {

    public static final int CHUNK_SIZE = 24576; // 24 KB

    public static class Transfer {
        public final String ownerUuid;
        public final String hash;
        public final int totalChunks;
        public final byte[][] chunks;
        public final AtomicInteger receivedCount = new AtomicInteger(0);
        public volatile long lastActivity = System.currentTimeMillis();

        public Transfer(String ownerUuid, String hash, int totalChunks) {
            this.ownerUuid = ownerUuid;
            this.hash = hash;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }

        public synchronized boolean addChunk(int index, byte[] data) {
            lastActivity = System.currentTimeMillis();
            if (index < 0 || index >= totalChunks) return false;
            if (chunks[index] == null) {
                chunks[index] = data;
                return receivedCount.incrementAndGet() == totalChunks;
            }
            return false;
        }

        public synchronized byte[] assemble() {
            if (receivedCount.get() != totalChunks) return null;
            int totalLen = 0;
            for (byte[] c : chunks) {
                if (c == null) return null;
                totalLen += c.length;
            }
            byte[] full = new byte[totalLen];
            int pos = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, full, pos, c.length);
                pos += c.length;
            }
            return full;
        }
    }

    private final Map<String, Transfer> activeTransfers = new ConcurrentHashMap<>();

    public synchronized byte[] handleIncomingChunk(String ownerUuid, String hash, int chunkIndex, int totalChunks, byte[] chunkData) {
        if (ownerUuid == null || hash == null || chunkData == null) return null;
        cleanExpired();

        String key = ownerUuid + ":" + hash;
        Transfer transfer = activeTransfers.computeIfAbsent(key, k -> new Transfer(ownerUuid, hash, totalChunks));
        if (transfer.addChunk(chunkIndex, chunkData)) {
            activeTransfers.remove(key);
            FiguraLink.LOGGER.info("[FiguraLimit] Successfully reassembled all {} chunks for avatar {}", totalChunks, ownerUuid);
            return transfer.assemble();
        }
        return null;
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        activeTransfers.entrySet().removeIf(e -> (now - e.getValue().lastActivity) > 60_000L);
    }

    @FunctionalInterface
    public interface ChunkSender {
        void send(String ownerUuid, String hash, int chunkIndex, int totalChunks, byte[] chunkData);
    }

    public static void streamChunks(String ownerUuid, String hash, byte[] data, ChunkSender sender) {
        if (ownerUuid == null || data == null || data.length == 0 || sender == null) return;
        if (hash == null || hash.isEmpty()) hash = "avatar";

        int totalChunks = (int) Math.ceil((double) data.length / CHUNK_SIZE);
        if (totalChunks <= 0) totalChunks = 1;

        FiguraLink.LOGGER.info("[FiguraLimit] Streaming avatar {} ({} bytes in {} chunks)...", ownerUuid, data.length, totalChunks);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, data.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(data, start, chunk, 0, chunk.length);

            try {
                sender.send(ownerUuid, hash, i, totalChunks, chunk);
            } catch (Throwable t) {
                FiguraLink.LOGGER.warn("[FiguraLimit] Error sending chunk " + i + " for " + ownerUuid + ": " + t.getMessage());
            }

            if (totalChunks > 5 && (i % 3 == 0)) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ignored) {}
            }
        }
        FiguraLink.LOGGER.info("[FiguraLimit] Finished streaming all {} chunks for avatar {}", totalChunks, ownerUuid);
    }
}
