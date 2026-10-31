package kr.spawnfilter.mixin;

import com.cobblemon.mod.common.api.spawning.SpawnBucket;
import com.cobblemon.mod.common.api.spawning.SpawnSet;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnPool;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import kr.spawnfilter.SpawnFilter;
import kr.spawnfilter.SpawnRates;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Filters the parsed spawn sets right before the pool turns them into its spawn details. */
@Mixin(value = SpawnPool.class, remap = false)
public abstract class SpawnPoolMixin {
    @Inject(method = "reload(Ljava/util/Map;)V", at = @At("HEAD"), remap = false)
    private void spawnfilter$filter(Map<Identifier, SpawnSet> sets, CallbackInfo ci) {
        SpawnRates.onSpawnSetsLoaded(sets.values());
        SpawnFilter.filter(((SpawnPool) (Object) this).getName(), sets);
    }

    @Inject(method = "reload(Ljava/util/Map;)V", at = @At("RETURN"), remap = false)
    private void spawnfilter$index(Map<Identifier, SpawnSet> sets, CallbackInfo ci) {
        SpawnRates.onPoolLoaded((SpawnPool) (Object) this);
    }

    /** Adds the forced species of the zones at the position, then filters per position (area, imported region maps). */
    @Inject(method = "retrieve", at = @At("RETURN"), cancellable = true, remap = false)
    private void spawnfilter$filterCandidates(SpawnBucket bucket, SpawnablePosition position,
                                              CallbackInfoReturnable<List<SpawnDetail>> cir) {
        List<SpawnDetail> candidates = new ArrayList<>(cir.getReturnValue());
        boolean added = SpawnRates.addForced((SpawnPool) (Object) this, bucket, position, candidates);
        int before = candidates.size();
        SpawnFilter.filterCandidates(position.getWorld(), position.getPosition(), candidates);

        if (added || candidates.size() != before) {
            cir.setReturnValue(candidates);
        }
    }
}
