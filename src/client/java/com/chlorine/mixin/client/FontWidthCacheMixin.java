package com.chlorine.mixin.client;

import com.chlorine.Chlorine;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caches widths for plain strings, which are frequently measured repeatedly
 * while drawing HUD and GUI text. Styled text uses a separate rendering path
 * and is intentionally not intercepted here.
 */
@Mixin(Font.class)
public abstract class FontWidthCacheMixin {
    @Unique
    private final Map<String, Integer> chlorine$widthCache =
            new LinkedHashMap<>(128, 0.75f, true);

    @Inject(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), cancellable = true)
    private void chlorine$cachedWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (!Chlorine.CONFIG.enableFontWidthCache || text == null) {
            return;
        }

        Integer cached = chlorine$widthCache.get(text);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

    }

    @Inject(method = "width(Ljava/lang/String;)I", at = @At("RETURN"))
    private void chlorine$storeWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (!Chlorine.CONFIG.enableFontWidthCache || text == null) {
            return;
        }

        int maxEntries = Math.max(0, Chlorine.CONFIG.fontWidthCacheSize);
        if (maxEntries == 0 || chlorine$widthCache.containsKey(text)) {
            return;
        }
        chlorine$widthCache.put(text, cir.getReturnValue());
        while (chlorine$widthCache.size() > maxEntries) {
            chlorine$widthCache.remove(chlorine$widthCache.keySet().iterator().next());
        }
    }
}
