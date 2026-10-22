package org.figuramc.figuralink.util;

public class FiguraLinkUtil {

    public static boolean isCustomBackend() {
        String address = getServerIp();
        return address != null && !address.isEmpty() && !address.contains("moonlight-devs.org");
    }

    public static String getServerIp() {
        try {
            Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
            Object serverIpConfig = configs.getField("SERVER_IP").get(null);
            return (String) serverIpConfig.getClass().getField("value").get(serverIpConfig);
        } catch (Throwable t) {
            return null;
        }
    }
}
