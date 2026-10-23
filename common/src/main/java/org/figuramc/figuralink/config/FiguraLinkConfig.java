package org.figuramc.figuralink.config;

import org.figuramc.figuralink.FiguraLink;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class FiguraLinkConfig {
    private static final String CONFIG_FILENAME = "figura-limit.properties";
    
    // Address sent to joining players (e.g. "127.0.0.1:8080" or "backend.myserver.com:8080")
    private static String backendAddress = "";
    // Full URL to backend HTTP API for presence reporting (e.g. "http://127.0.0.1:8080")
    private static String backendUrl = "";
    // Server secret for authenticating with the Fastify backend dashboard
    private static String serverSecret = "";
    // Default 5 MB
    private static int maxAvatarSize = 5 * 1024 * 1024;

    public static String getBackendAddress() {
        return backendAddress;
    }

    public static String getBackendUrl() {
        return backendUrl;
    }

    public static String getServerSecret() {
        return serverSecret;
    }

    public static int getMaxAvatarSize() {
        return maxAvatarSize;
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
                backendAddress = props.getProperty("backend-address", "").trim();
                if (backendAddress.isEmpty()) {
                    backendAddress = props.getProperty("public-backend-address", "").trim();
                }
                backendUrl = props.getProperty("backend-url", "").trim();
                serverSecret = props.getProperty("server-secret", "").trim();
                String sizeStr = props.getProperty("max-avatar-size", "5MB").trim();

                maxAvatarSize = (int) parseBytes(sizeStr, 5 * 1024 * 1024);
                FiguraLink.LOGGER.info("[FiguraLimit] Loaded config: backend-address='{}', max-avatar-size={} bytes", 
                        backendAddress, maxAvatarSize);
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
        return inConfig;
    }

    private static void saveDefault(Path target) {
        try {
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }

            String content = """
                    # ========================================================
                    # Figura Limit Companion Mod Configuration
                    # ========================================================
                    # The address of your standalone Figura Backend server
                    # Clients connecting to this Minecraft server will automatically switch
                    # their Figura Cloud to this backend address.
                    # Example: backend-address=127.0.0.1:8080 or backend-address=figura.myhost.com:8080
                    backend-address=

                    # Optional: Standalone Backend URL for sending server presence (online players)
                    # to the Admin Dashboard.
                    # Example: backend-url=http://127.0.0.1:8080
                    backend-url=

                    # Optional: Server Secret matching your instance secret in the backend
                    server-secret=

                    # Maximum avatar file size allowed for client avatars
                    # Supports units like: 5MB, 10MB, 20MB, or raw bytes (e.g. 5242880)
                    max-avatar-size=5MB
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
}
