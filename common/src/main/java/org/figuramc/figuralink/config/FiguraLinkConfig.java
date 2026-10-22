package org.figuramc.figuralink.config;

import org.figuramc.figuralink.FiguraLink;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.stream.Stream;

public class FiguraLinkConfig {
    private static final String CONFIG_FILENAME = "figura-limit.properties";
    
    // Default 30007
    private static int backendPort = 30007;
    // Public backend address for playit.gg or tunnel services
    private static String publicBackendAddress = "";
    // Default 5 MB
    private static int maxAvatarSize = 5 * 1024 * 1024;
    // Default 50 GB
    private static long totalStorageLimit = 50L * 1024L * 1024L * 1024L;

    public static int getBackendPort() {
        return backendPort;
    }

    public static String getPublicBackendAddress() {
        return publicBackendAddress;
    }

    public static int getMaxAvatarSize() {
        return maxAvatarSize;
    }

    public static long getTotalStorageLimit() {
        return totalStorageLimit;
    }

    public static boolean isClientEnvironment() {
        try {
            Class<?> flClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object fl = flClass.getMethod("getInstance").invoke(null);
            Object envType = flClass.getMethod("getEnvironmentType").invoke(fl);
            if ("CLIENT".equalsIgnoreCase(String.valueOf(envType))) {
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> fmlEnv = Class.forName("net.minecraftforge.fml.loading.FMLEnvironment");
            Object dist = fmlEnv.getField("dist").get(null);
            if ("CLIENT".equalsIgnoreCase(String.valueOf(dist))) {
                return true;
            }
        } catch (Throwable ignored) {}

        try {
            Class.forName("net.minecraft.client.MinecraftClient");
            return true;
        } catch (Throwable ignored) {}

        try {
            Class.forName("net.minecraft.class_310");
            return true;
        } catch (Throwable ignored) {}

        return false;
    }

    public static void load() {
        Path configPath = getConfigPath();
        Properties props = new Properties();

        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
                String portStr = props.getProperty("backend-port", "30007").trim();
                publicBackendAddress = props.getProperty("public-backend-address", "").trim();
                String sizeStr = props.getProperty("max-avatar-size", "5MB").trim();
                String storageStr = props.getProperty("total-storage-limit", "50GB").trim();

                try {
                    backendPort = Integer.parseInt(portStr);
                } catch (Exception ignored) {
                    backendPort = 30007;
                }
                maxAvatarSize = (int) parseBytes(sizeStr, 5 * 1024 * 1024);
                totalStorageLimit = parseBytes(storageStr, 50L * 1024L * 1024L * 1024L);
                return;
            } catch (Exception e) {
                FiguraLink.LOGGER.error("[FiguraLimit] Failed to read " + CONFIG_FILENAME + ", using defaults.", e);
            }
        }

        // On client: skip creating default file
        if (isClientEnvironment()) {
            return;
        }

        // Create default file ONLY on dedicated server
        saveDefault(configPath);
    }

    private static Path getConfigPath() {
        Path inConfig = Paths.get("config", CONFIG_FILENAME);
        if (Files.exists(inConfig)) {
            return inConfig;
        }
        Path inRoot = Paths.get(CONFIG_FILENAME);
        if (Files.exists(inRoot)) {
            return inRoot;
        }
        // Standard Minecraft server mod location: config/figura-limit.properties
        return inConfig;
    }

    private static void saveDefault(Path target) {
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }

            String content = """
                    # ========================================================
                    # Figura Limit Unlocked Configuration
                    # ========================================================
                    # Port for the embedded Figura backend server
                    backend-port=30007

                    # Optional: Public address for tunnel services (e.g. playit.gg, ngrok)
                    # If left empty, clients will automatically connect to this server's IP and backend-port.
                    # Example: public-backend-address=signaling-descriptive.tun.ply.gg:12345
                    public-backend-address=

                    # Maximum avatar file size allowed on this server
                    # Supports units like: 5MB, 10MB, 20MB, or raw bytes (e.g. 5242880)
                    max-avatar-size=5MB

                    # Total storage limit for all avatars on this server
                    # Supports units like: 50GB, 100GB, or raw bytes (e.g. 53687091200)
                    total-storage-limit=50GB
                    """;

            Files.writeString(target, content);
            FiguraLink.LOGGER.info("[FiguraLimit] Created default server config at: " + target.toAbsolutePath());
        } catch (Exception e) {
            FiguraLink.LOGGER.error("[FiguraLimit] Failed to create default " + CONFIG_FILENAME, e);
        }
    }

    public static long parseBytes(String input, long fallback) {
        if (input == null || input.isEmpty()) return fallback;
        try {
            String upper = input.toUpperCase().trim();
            long multiplier = 1;
            String numPart = upper;

            if (upper.endsWith("TB")) {
                multiplier = 1024L * 1024L * 1024L * 1024L;
                numPart = upper.substring(0, upper.length() - 2).trim();
            } else if (upper.endsWith("GB")) {
                multiplier = 1024L * 1024L * 1024L;
                numPart = upper.substring(0, upper.length() - 2).trim();
            } else if (upper.endsWith("MB")) {
                multiplier = 1024L * 1024L;
                numPart = upper.substring(0, upper.length() - 2).trim();
            } else if (upper.endsWith("KB")) {
                multiplier = 1024L;
                numPart = upper.substring(0, upper.length() - 2).trim();
            } else if (upper.endsWith("B")) {
                numPart = upper.substring(0, upper.length() - 1).trim();
            }

            double val = Double.parseDouble(numPart);
            return (long) (val * multiplier);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean isTotalStorageExceeded(Path avatarsFolder) {
        if (avatarsFolder == null || !Files.exists(avatarsFolder)) return false;
        try (Stream<Path> stream = Files.walk(avatarsFolder)) {
            long totalBytes = stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
            return totalBytes >= totalStorageLimit;
        } catch (Exception e) {
            return false;
        }
    }
}
