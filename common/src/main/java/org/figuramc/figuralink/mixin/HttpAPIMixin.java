package org.figuramc.figuralink.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.URI;

@Mixin(targets = "org.figuramc.figura.backend2.HttpAPI", remap = false)
public class HttpAPIMixin {

    @Inject(method = "getUri", at = @At("HEAD"), cancellable = true)
    private static void onGetUri(String path, CallbackInfoReturnable<URI> cir) {
        String address = getServerIp();
        if (address != null && !address.isEmpty() && !address.equalsIgnoreCase("figura.moonlight-devs.org")) {
            String clean = address.replaceFirst("^(http://|https://)", "");
            String scheme = address.startsWith("https://") ? "https://" : "http://";
            cir.setReturnValue(URI.create(scheme + clean + "/api/" + path));
        }
    }

    private static String getServerIp() {
        try {
            Class<?> configs = Class.forName("org.figuramc.figura.config.Configs");
            Object serverIpConfig = configs.getField("SERVER_IP").get(null);
            return (String) serverIpConfig.getClass().getField("value").get(serverIpConfig);
        } catch (Throwable t) {
            return null;
        }
    }
}
