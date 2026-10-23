package org.figuramc.figuralink.server;

import org.figuramc.figuralink.FiguraLink;
import org.figuramc.figuralink.config.FiguraLinkConfig;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class FiguraDriveBossBar {

    private static Object sharedBossBar = null;
    private static final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Object> viewerPlayers = new ConcurrentHashMap<>();
    private static volatile boolean updaterRunning = false;

    public static class DriveStats {
        public int avatarCount = 0;
        public int userCount = 0;
        public long totalBytes = 0;
        public long totalLimit = FiguraLinkConfig.getTotalStorageLimit();
        public double percent = 0.0;
    }

    public static DriveStats getDriveStats() {
        DriveStats stats = new DriveStats();

        // 1. In-memory ephemeral RAM cache stats
        int memUsers = EmbeddedFiguraServer.getMemoryUserCount();
        long memBytes = EmbeddedFiguraServer.getMemoryTotalBytes();

        // 2. Disk avatars stats
        Path avatarsDir = Paths.get("figura", "avatars");
        if (!Files.exists(avatarsDir)) {
            avatarsDir = Paths.get("figura-avatars");
        }
        int diskAvatars = 0;
        int diskUsers = 0;
        long diskBytes = 0;
        if (Files.exists(avatarsDir)) {
            try (Stream<Path> stream = Files.walk(avatarsDir)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                diskAvatars = files.size();
                for (Path p : files) {
                    try {
                        diskBytes += Files.size(p);
                    } catch (IOException ignored) {}
                }
            } catch (Exception ignored) {}
            try (Stream<Path> stream = Files.list(avatarsDir)) {
                diskUsers = (int) stream.filter(Files::isDirectory).count();
            } catch (Exception ignored) {}
        }

        stats.avatarCount = memUsers + diskAvatars;
        stats.userCount = Math.max(memUsers, diskUsers);
        stats.totalBytes = memBytes + diskBytes;

        if (stats.totalLimit > 0) {
            stats.percent = (stats.totalBytes * 100.0) / (double) stats.totalLimit;
        }
        return stats;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static synchronized void togglePlayer(Object serverPlayer, Object optionalSource) {
        if (serverPlayer == null) {
            if (optionalSource != null) {
                printConsoleStats(optionalSource);
            }
            return;
        }

        if (FiguraServerSync.currentServer != null) {
            try {
                Method exec = FiguraServerSync.currentServer.getClass().getMethod("execute", Runnable.class);
                exec.invoke(FiguraServerSync.currentServer, (Runnable) () -> doTogglePlayer(serverPlayer, optionalSource));
                return;
            } catch (Throwable ignored) {}
        }
        doTogglePlayer(serverPlayer, optionalSource);
    }

    private static synchronized void doTogglePlayer(Object serverPlayer, Object optionalSource) {
        UUID uuid = getPlayerUuid(serverPlayer);
        if (uuid == null) return;

        if (viewers.contains(uuid)) {
            // Turn OFF
            viewers.remove(uuid);
            viewerPlayers.remove(uuid);
            removePlayerFromBossBar(serverPlayer);
            sendPlayerMessage(serverPlayer, "§e[Figura Drive] BossBar hidden.");
            if (viewers.isEmpty() && sharedBossBar != null) {
                setBossBarVisible(sharedBossBar, false);
            }
        } else {
            // Turn ON
            viewers.add(uuid);
            viewerPlayers.put(uuid, serverPlayer);
            ensureBossBarInitialized();
            updateBossBar();
            addPlayerToBossBar(serverPlayer);
            setBossBarVisible(sharedBossBar, true);
            sendPlayerMessage(serverPlayer, "§a[Figura Drive] BossBar enabled. Run /figura drive again to hide.");
            startAutoUpdater();
        }
    }

    public static synchronized void updateBossBar() {
        if (sharedBossBar == null || viewers.isEmpty()) return;

        Runnable updateTask = () -> {
            DriveStats stats = getDriveStats();
            String formattedUsed = formatBytes(stats.totalBytes);
            String formattedLimit = formatBytes(stats.totalLimit);
            String title = String.format(Locale.ROOT, "§bFigura Drive: §e%d Avatars §7| §a%s §7/ §f%s §6(%.1f%%)",
                    stats.avatarCount, formattedUsed, formattedLimit, stats.percent);

            float barPercent = (float) Math.max(0.0, Math.min(1.0, (double) stats.totalBytes / (double) Math.max(1L, stats.totalLimit)));
            String colorName = stats.percent < 50.0 ? "GREEN" : (stats.percent < 80.0 ? "YELLOW" : "RED");

            setBossBarName(sharedBossBar, title);
            setBossBarPercent(sharedBossBar, barPercent);
            setBossBarColor(sharedBossBar, colorName);
        };

        if (FiguraServerSync.currentServer != null) {
            try {
                Method exec = FiguraServerSync.currentServer.getClass().getMethod("execute", Runnable.class);
                exec.invoke(FiguraServerSync.currentServer, updateTask);
                return;
            } catch (Throwable ignored) {}
        }
        updateTask.run();
    }

    public static synchronized void onPlayerDisconnect(Object serverPlayer) {
        if (serverPlayer == null) return;
        UUID uuid = getPlayerUuid(serverPlayer);
        if (uuid != null) {
            viewers.remove(uuid);
            viewerPlayers.remove(uuid);
        }
        if (sharedBossBar != null) {
            removePlayerFromBossBar(serverPlayer);
            if (viewers.isEmpty()) {
                setBossBarVisible(sharedBossBar, false);
            }
        }
    }

    public static void printConsoleStats(Object source) {
        DriveStats stats = getDriveStats();
        String message = String.format(Locale.ROOT, "[Figura Drive] %d Avatars (%d Players) | Storage: %s / %s (%.1f%% used)",
                stats.avatarCount, stats.userCount,
                formatBytes(stats.totalBytes),
                formatBytes(stats.totalLimit),
                stats.percent);
        FiguraLink.LOGGER.info(message);
        try {
            Class<?> textClass = null;
            try { textClass = Class.forName("net.minecraft.text.Text"); }
            catch (Throwable e) { textClass = Class.forName("net.minecraft.class_2561"); }
            Object text = createText(message);
            if (text != null) {
                for (Method m : source.getClass().getMethods()) {
                    if (m.getName().toLowerCase().contains("feedback") || m.getName().toLowerCase().contains("sendmessage")) {
                        if (m.getParameterCount() == 2 && m.getParameterTypes()[1] == boolean.class) {
                            java.util.function.Supplier<?> s = () -> text;
                            m.invoke(source, s, false);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void startAutoUpdater() {
        if (updaterRunning) return;
        updaterRunning = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    if (!viewers.isEmpty()) {
                        updateBossBar();
                    }
                } catch (Throwable ignored) {}
            }
        }, "FiguraLimit-DriveUpdater");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void ensureBossBarInitialized() {
        if (sharedBossBar != null) return;
        try {
            Class<?> bossBarClass = null;
            for (String cName : new String[]{
                "net.minecraft.entity.boss.ServerBossBar",
                "net.minecraft.class_3213",
                "net.minecraft.server.level.ServerBossEvent"
            }) {
                try {
                    bossBarClass = Class.forName(cName);
                    break;
                } catch (Throwable ignored) {}
            }

            if (bossBarClass == null) {
                FiguraLink.LOGGER.warn("[FiguraLimit] Could not find ServerBossBar class.");
                return;
            }

            Constructor<?> ctor = null;
            for (Constructor<?> c : bossBarClass.getConstructors()) {
                if (c.getParameterCount() == 3) {
                    ctor = c;
                    break;
                }
            }

            if (ctor == null && bossBarClass.getConstructors().length > 0) {
                ctor = bossBarClass.getConstructors()[0];
            }

            if (ctor != null) {
                Class<?>[] params = ctor.getParameterTypes();
                Object text = createText("§bFigura Drive");
                Object color = getBossBarColor(params.length > 1 ? params[1] : null, "GREEN");
                Object style = getBossBarStyle(params.length > 2 ? params[2] : null, "PROGRESS");

                sharedBossBar = ctor.newInstance(text, color, style);
                setBossBarVisible(sharedBossBar, true);
                FiguraLink.LOGGER.info("[FiguraLimit] Initialized ServerBossBar for /figura drive.");
            }
        } catch (Throwable t) {
            FiguraLink.LOGGER.error("[FiguraLimit] Failed to instantiate ServerBossBar", t);
        }
    }

    public static Object createText(String message) {
        for (String className : new String[]{"net.minecraft.text.Text", "net.minecraft.class_2561", "net.minecraft.network.chat.Component"}) {
            try {
                Class<?> clazz = Class.forName(className);
                for (String mName : new String[]{"literal", "method_43470"}) {
                    try {
                        Method m = clazz.getMethod(mName, String.class);
                        return m.invoke(null, message);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static Object getBossBarColor(Class<?> colorClass, String name) {
        if (colorClass == null) return null;
        try {
            for (Object c : (Object[]) colorClass.getMethod("values").invoke(null)) {
                String cName = c.toString();
                try {
                    Method mName = c.getClass().getMethod("method_5421");
                    cName = (String) mName.invoke(c);
                } catch (Throwable ignored) {}
                if (cName.equalsIgnoreCase(name) || c.toString().equalsIgnoreCase(name)) {
                    return c;
                }
            }
        } catch (Throwable ignored) {}
        Object[] constants = colorClass.getEnumConstants();
        return (constants != null && constants.length > 0) ? constants[0] : null;
    }

    public static Object getBossBarStyle(Class<?> styleClass, String name) {
        if (styleClass == null) return null;
        try {
            for (Object s : (Object[]) styleClass.getMethod("values").invoke(null)) {
                String sName = s.toString();
                try {
                    Method mName = s.getClass().getMethod("method_5425");
                    sName = (String) mName.invoke(s);
                } catch (Throwable ignored) {}
                if (sName.equalsIgnoreCase(name) || s.toString().equalsIgnoreCase(name)) {
                    return s;
                }
            }
        } catch (Throwable ignored) {}
        Object[] constants = styleClass.getEnumConstants();
        return (constants != null && constants.length > 0) ? constants[0] : null;
    }

    private static void setBossBarName(Object bar, String title) {
        if (bar == null) return;
        Object text = createText(title);
        if (text == null) return;
        for (String mName : new String[]{"setName", "method_5413"}) {
            try {
                for (Method m : bar.getClass().getMethods()) {
                    if (m.getName().equals(mName) && m.getParameterCount() == 1) {
                        m.invoke(bar, text);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void setBossBarPercent(Object bar, float percent) {
        if (bar == null) return;
        for (String mName : new String[]{"setPercent", "method_5408"}) {
            try {
                for (Method m : bar.getClass().getMethods()) {
                    if (m.getName().equals(mName) && m.getParameterCount() == 1) {
                        m.invoke(bar, percent);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void setBossBarColor(Object bar, String colorName) {
        if (bar == null) return;
        for (String mName : new String[]{"setColor", "method_5416"}) {
            try {
                for (Method m : bar.getClass().getMethods()) {
                    if (m.getName().equals(mName) && m.getParameterCount() == 1) {
                        Object col = getBossBarColor(m.getParameterTypes()[0], colorName);
                        if (col != null) {
                            m.invoke(bar, col);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void setBossBarVisible(Object bar, boolean visible) {
        if (bar == null) return;
        for (String mName : new String[]{"setVisible", "method_14091"}) {
            try {
                for (Method m : bar.getClass().getMethods()) {
                    if (m.getName().equals(mName) && m.getParameterCount() == 1) {
                        m.invoke(bar, visible);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void addPlayerToBossBar(Object player) {
        if (sharedBossBar == null || player == null) return;
        for (String mName : new String[]{"addPlayer", "method_14088"}) {
            try {
                for (Method m : sharedBossBar.getClass().getMethods()) {
                    if (m.getName().equals(mName) && m.getParameterCount() == 1) {
                        try {
                            m.invoke(sharedBossBar, player);
                            FiguraLink.LOGGER.info("[FiguraLimit] Successfully added player to BossBar.");
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void removePlayerFromBossBar(Object player) {
        if (sharedBossBar == null || player == null) return;
        for (String mName : new String[]{"removePlayer", "method_14089"}) {
            try {
                for (Method m : sharedBossBar.getClass().getMethods()) {
                    if (m.getName().equals(mName) && m.getParameterCount() == 1) {
                        try {
                            m.invoke(sharedBossBar, player);
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static UUID getPlayerUuid(Object serverPlayer) {
        if (serverPlayer == null) return null;
        for (String mName : new String[]{"getUuid", "method_5667", "getGameProfile"}) {
            try {
                Method m = serverPlayer.getClass().getMethod(mName);
                Object res = m.invoke(serverPlayer);
                if (res instanceof UUID) return (UUID) res;
                if (res != null) {
                    Method getId = res.getClass().getMethod("getId");
                    Object id = getId.invoke(res);
                    if (id instanceof UUID) return (UUID) id;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void sendPlayerMessage(Object serverPlayer, String message) {
        if (serverPlayer == null) return;
        Object text = createText(message);
        if (text == null) return;

        for (String mName : new String[]{"sendMessage", "method_7353", "sendSystemMessage"}) {
            try {
                Method m = serverPlayer.getClass().getMethod(mName, text.getClass(), boolean.class);
                m.invoke(serverPlayer, text, false);
                return;
            } catch (Throwable ignored) {}
        }
        for (String mName : new String[]{"sendMessage", "method_43496", "sendSystemMessage"}) {
            try {
                Method m = serverPlayer.getClass().getMethod(mName, text.getClass());
                m.invoke(serverPlayer, text);
                return;
            } catch (Throwable ignored) {}
        }
    }
}
