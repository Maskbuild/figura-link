package org.figuramc.figuralink;

import org.figuramc.figuralink.client.FiguraClientSync;
import org.figuramc.figuralink.config.FiguraLinkConfig;
import org.figuramc.figuralink.server.EmbeddedFiguraServer;
import org.figuramc.figuralink.server.FiguraServerSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FiguraLink {
    public static final String MOD_ID = "figuralink";
    public static final String MOD_NAME = "Figura Limit Unlocked";
    public static final String MOD_VERSION = "1.1.4";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public static void init() {
        LOGGER.info("[FiguraLimit] Initializing Figura Limit Unlocked v{}...", MOD_VERSION);
        FiguraLinkConfig.load();
        LOGGER.info("[FiguraLimit] Max avatar size set to: {} bytes ({} MB)", 
                FiguraLinkConfig.getMaxAvatarSize(), 
                FiguraLinkConfig.getMaxAvatarSize() / (1024 * 1024));
        LOGGER.info("[FiguraLimit] Total server storage limit set to: {} bytes ({} GB)", 
                FiguraLinkConfig.getTotalStorageLimit(), 
                FiguraLinkConfig.getTotalStorageLimit() / (1024L * 1024L * 1024L));

        if (!FiguraLinkConfig.isClientEnvironment()) {
            EmbeddedFiguraServer.start(FiguraLinkConfig.getBackendPort());
            FiguraServerSync.init();
        } else {
            // Start local backend on client for singleplayer & offline fallback
            EmbeddedFiguraServer.start(FiguraLinkConfig.getBackendPort());
            FiguraClientSync.init();
        }
    }
}
