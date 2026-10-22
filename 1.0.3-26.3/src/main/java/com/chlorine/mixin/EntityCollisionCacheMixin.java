package com.chlorine.mixin;

import com.chlorine.CollisionCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(Entity.class)
public abstract class EntityCollisionCacheMixin {
    @WrapOperation(
            method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"),
            require = 0
    )
    private List<VoxelShape> chlorine$cacheStationaryCollisions(
            Level level, Entity entity, AABB box, Operation<List<VoxelShape>> original) {
        List<VoxelShape> cached = CollisionCache.get(entity, box);
        if (cached != null) {
            return cached;
        }
        List<VoxelShape> result = original.call(level, entity, box);
        CollisionCache.put(entity, box, result);
        return result;
    }
}
