package org.figuramc.figuralink.fabric;

import net.fabricmc.api.ModInitializer;
import org.figuramc.figuralink.FiguraLink;

public class FiguraLinkFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FiguraLink.init();
    }
}
