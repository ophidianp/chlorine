package com.chlorine.mixin.client;

import com.chlorine.Chlorine;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips sign text beyond a readable distance.
 *
 * Vanilla draws every sign's text (each line split into glyph quads) for
 * every sign within the 64-block block entity range, though it's
 * unreadable past ~20 blocks. Storage rooms with walls of labelled signs
 * pay for all of it each frame. The sign board is still drawn, only the
 * text is skipped. Covers both standing/wall and hanging signs, since
 * both go through AbstractSignRenderer.submitSignText (once per side).
 */
@Mixin(AbstractSignRenderer.class)
public abstract class SignTextLodMixin {

    @Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
    private void chlorine$skipDistantSignText(
            SignRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            SignText text,
            CallbackInfo ci) {
        if (!Chlorine.CONFIG.enableSignTextLod || state.blockPos == null) {
            return;
        }

        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        double dx = camera.x - (state.blockPos.getX() + 0.5);
        double dy = camera.y - (state.blockPos.getY() + 0.5);
        double dz = camera.z - (state.blockPos.getZ() + 0.5);
        double limit = Chlorine.CONFIG.signTextRenderDistance;

        if (dx * dx + dy * dy + dz * dz > limit * limit) {
            ci.cancel();
        }
    }
}
