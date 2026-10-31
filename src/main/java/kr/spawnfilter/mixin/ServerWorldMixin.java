package kr.spawnfilter.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import kr.spawnfilter.SpawnFilter;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks every pokemon that appears while the server runs (natural spawns, outbreaks, legendaries, commands), so the
 * cleanup of pokemon already saved in the world never removes them: removing one right after it appeared leaves a
 * frozen copy on the players' screens.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {
    @Inject(method = "addEntity", at = @At("HEAD"))
    private void spawnfilter$markSpawned(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof PokemonEntity) {
            entity.addCommandTag(SpawnFilter.SPAWNED_TAG);
        }
    }
}
