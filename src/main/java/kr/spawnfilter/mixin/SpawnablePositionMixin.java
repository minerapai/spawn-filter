package kr.spawnfilter.mixin;

import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import kr.spawnfilter.SpawnRates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the spawn rate multipliers (whole server and zones) to the weight of a spawn candidate at its position. */
@Mixin(value = SpawnablePosition.class, remap = false)
public abstract class SpawnablePositionMixin {
    @Inject(method = "getWeight(Lcom/cobblemon/mod/common/api/spawning/detail/SpawnDetail;)F", at = @At("RETURN"),
        cancellable = true, remap = false)
    private void spawnfilter$zoneMultiplier(SpawnDetail detail, CallbackInfoReturnable<Float> cir) {
        SpawnablePosition self = (SpawnablePosition) (Object) this;
        float multiplier = SpawnRates.multiplierAt(self.getWorld(), self.getPosition(), detail);

        if (multiplier != 1f) {
            cir.setReturnValue(cir.getReturnValue() * multiplier);
        }
    }
}
