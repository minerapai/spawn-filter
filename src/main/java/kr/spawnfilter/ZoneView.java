package kr.spawnfilter;

import kr.spawnfilter.net.RatesPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sends the zones (and the region filter area) to OP clients that turned on the zone display. */
public final class ZoneView {
    private static final Set<UUID> VIEWERS = ConcurrentHashMap.newKeySet();

    private ZoneView() {
    }

    static void request(ServerPlayerEntity player, boolean show) {
        if (!show) {
            VIEWERS.remove(player.getUuid());
            return;
        }

        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("[스폰 필터] 구역 표시는 OP만 쓸 수 있습니다.").formatted(Formatting.RED));
            ServerPlayNetworking.send(player, new RatesPayloads.View(List.of(), null));
            return;
        }

        VIEWERS.add(player.getUuid());
        ServerPlayNetworking.send(player, snapshot());
    }

    static void remove(ServerPlayerEntity player) {
        VIEWERS.remove(player.getUuid());
    }

    /** Sends the current zones to everyone who is viewing them (call after any change). */
    static void broadcast(MinecraftServer server) {
        if (VIEWERS.isEmpty()) {
            return;
        }

        RatesPayloads.View view = snapshot();

        for (UUID id : VIEWERS) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);

            if (player != null) {
                ServerPlayNetworking.send(player, view);
            }
        }
    }

    private static RatesPayloads.View snapshot() {
        List<RatesPayloads.ZoneData> zones = new ArrayList<>();

        for (SpawnRates.Zone z : SpawnRates.zones()) {
            if (z.area) {
                zones.add(new RatesPayloads.ZoneData(z.name, true, z.dimension, z.minX, z.minZ, z.maxX, z.maxZ, z.multipliers,
                    List.copyOf(z.forced)));
            }
        }

        SpawnFilterConfig.Area area = SpawnFilterConfig.area();
        RatesPayloads.ZoneData filterArea = area == null ? null : new RatesPayloads.ZoneData("지방 필터 경계", true,
            SpawnRates.OVERWORLD, area.minX(), area.minZ(), area.maxX(), area.maxZ(), Map.of(), List.of());
        return new RatesPayloads.View(zones, filterArea);
    }
}
