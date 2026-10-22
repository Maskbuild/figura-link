package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.util.FiguraLinkUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "org.figuramc.figura.gui.screens.WardrobeScreen", remap = false)
public class WardrobeScreenMixin {

    @Shadow
    private Object upload;

    @Inject(method = {"tick", "method_25393", "render", "method_25394"}, at = @At("TAIL"), remap = false, require = 0)
    private void onScreenUpdate(CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend() && upload != null) {
            try {
                Method m = upload.getClass().getMethod("setActive", boolean.class);
                m.invoke(upload, true);
            } catch (Throwable ignored) {}

            try {
                Class<?> clazz = upload.getClass();
                while (clazz != null && clazz != Object.class) {
                    for (Field f : clazz.getDeclaredFields()) {
                        if (f.getType() == boolean.class && (f.getName().equals("field_22763") || f.getName().equals("active"))) {
                            f.setAccessible(true);
                            f.setBoolean(upload, true);
                        }
                    }
                    clazz = clazz.getSuperclass();
                }
            } catch (Throwable ignored) {}

            try {
                Class<?> am = Class.forName("org.figuramc.figura.avatar.AvatarManager");
                Field lu = am.getDeclaredField("localUploaded");
                lu.setAccessible(true);
                lu.setBoolean(null, false);
            } catch (Throwable ignored) {}
        }
    }
}
