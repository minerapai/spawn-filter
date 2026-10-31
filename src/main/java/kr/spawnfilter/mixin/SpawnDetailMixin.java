package kr.spawnfilter.mixin;

import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import kr.spawnfilter.SpawnRates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Species forced in a zone ignore their spawn conditions (biome, time, weather, ...) inside that zone. */
@Mixin(value = SpawnDetail.class, remap = false)
public abstract class SpawnDetailMixin {
    @Inject(method = "isSatisfiedBy", at = @At("HEAD"), cancellable = true, remap = false)
    private void spawnfilter$forced(SpawnablePosition position, CallbackInfoReturnable<Boolean> cir) {
        SpawnDetail self = (SpawnDetail) (Object) this;

        if (SpawnRates.isForcedAt(position.getWorld(), position.getPosition(), self)) {
            cir.setReturnValue(position.preFilter(self)); // spawning influences (e.g. repel effects) still apply
        }
    }
}
