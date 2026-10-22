package org.figuramc.figuralink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;

@Mixin(targets = "org.figuramc.figura.backend2.HttpAPI", remap = false)
public class HttpAPIMixin {

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

    @Inject(method = "getUri", at = @At("HEAD"), cancellable = true)
    private static void onGetUri(String path, CallbackInfoReturnable<URI> cir) {
        if (isCustomBackend()) {
            String address = getServerIp();
            if (address != null && !address.isEmpty()) {
                String clean = address.replaceFirst("^(http://|https://)", "");
                String scheme = address.startsWith("https://") ? "https://" : "http://";
                String normPath = (path != null && path.startsWith("/")) ? path : ("/" + (path == null ? "" : path));
                cir.setReturnValue(URI.create(scheme + clean + "/api" + normPath));
            }
        }
    }
}
