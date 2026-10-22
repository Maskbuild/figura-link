package org.figuramc.figuralink.mixin;

import org.figuramc.figuralink.util.FiguraLinkUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(targets = "org.figuramc.figura.gui.screens.WardrobeScreen", remap = false)
public class WardrobeScreenMixin {

    @Inject(method = {"tick", "method_25393"}, at = @At("TAIL"), remap = false, require = 0)
    private void onTick(CallbackInfo ci) {
        if (FiguraLinkUtil.isCustomBackend()) {
            try {
                Field uploadField = null;
                for (Field f : this.getClass().getDeclaredFields()) {
                    if (f.getName().equals("upload")) {
                        uploadField = f;
                        break;
                    }
                }
                if (uploadField != null) {
                    uploadField.setAccessible(true);
                    Object uploadBtn = uploadField.get(this);
                    if (uploadBtn != null) {
                        Method setActive = uploadBtn.getClass().getMethod("setActive", boolean.class);
                        setActive.invoke(uploadBtn, true);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}
