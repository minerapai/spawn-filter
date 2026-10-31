package kr.spawnfilter;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.spawning.SpawnSet;
import com.cobblemon.mod.common.api.spawning.detail.PokemonHerdSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.spawnfilter.net.RatesPayloads;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Removes spawn entries of pokemon outside the configured regions when Cobblemon loads its spawn pools. */
public class SpawnFilter implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("spawn-filter");
    /** Command tag of pokemon that appeared while the server ran (see ServerWorldMixin); the cleanup leaves them. */
    public static final String SPAWNED_TAG = "spawnfilter_spawned";

    private static volatile String lastSummary = "not applied yet";
    private static final AtomicInteger removedExisting = new AtomicInteger();

    @Override
    public void onInitialize() {
        SpawnFilterConfig.enabled(); // create the config file on startup
        ServerEntityEvents.ENTITY_LOAD.register(SpawnFilter::onEntityLoad);
        PayloadTypeRegistry.playS2C().register(RatesPayloads.Snapshot.ID, RatesPayloads.Snapshot.CODEC);
        PayloadTypeRegistry.playC2S().register(RatesPayloads.Apply.ID, RatesPayloads.Apply.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RatesPayloads.Apply.ID, (payload, context) -> applyRates(context.player(), payload));
        PayloadTypeRegistry.playS2C().register(RatesPayloads.View.ID, RatesPayloads.View.CODEC);
        PayloadTypeRegistry.playC2S().register(RatesPayloads.ViewRequest.ID, RatesPayloads.ViewRequest.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RatesPayloads.ViewRequest.ID, (payload, context) -> ZoneView.request(context.player(), payload.show()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ZoneView.remove(handler.getPlayer()));
        // Cobblemon sets its bucket weights when the server starts, apply ours on top of them
        ServerLifecycleEvents.SERVER_STARTED.register(server -> SpawnRates.applyBuckets());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> SpawnRates.applyBuckets());
        // Functions of imported region maps can reference their datapack NPCs, which Cobblemon only knows
        // after the first data load, so those functions fail to parse at startup. One reload fixes them.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!SpawnFilterConfig.dimensionPools().isEmpty()) {
                LOGGER.info("reloading data once so imported map functions can resolve their NPCs");
                server.getCommandManager().executeWithPrefix(server.getCommandSource(), "reload");
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("spawnfilter")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(SpawnFilter::show)
                .then(CommandManager.literal("enabled")
                    .then(CommandManager.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            SpawnFilterConfig.setEnabled(BoolArgumentType.getBool(ctx, "value"));
                            return changed(ctx);
                        })))
                .then(CommandManager.literal("removeExisting")
                    .then(CommandManager.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            SpawnFilterConfig.setRemoveExisting(BoolArgumentType.getBool(ctx, "value"));
                            return show(ctx);
                        })))
                .then(CommandManager.literal("regions")
                    .then(CommandManager.argument("list", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            Region.ids().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            Set<String> unknown = SpawnFilterConfig.setRegions(StringArgumentType.getString(ctx, "list").replace(' ', ','));

                            if (!unknown.isEmpty()) {
                                ctx.getSource().sendError(Text.literal("[Spawn Filter] unknown region(s): " + unknown
                                    + " (valid: " + String.join(", ", Region.ids()) + ")"));
                                return 0;
                            }

                            return changed(ctx);
                        })))
                .then(CommandManager.literal("cleanup")
                    .executes(ctx -> {
                        int removed = cleanupLoaded(ctx.getSource().getServer());
                        ctx.getSource().sendFeedback(() -> Text.literal("[Spawn Filter] removed " + removed
                            + " wild pokemon that do not match the current regions"), true);
                        return removed;
                    }))
                .then(CommandManager.literal("rates")
                    .executes(ctx -> openRates(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("ui")
                    .executes(ctx -> {
                        RegionMenu.open(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    }))
                .then(CommandManager.literal("area")
                    .then(CommandManager.literal("clear")
                        .executes(ctx -> {
                            SpawnFilterConfig.setArea(null);
                            ZoneView.broadcast(ctx.getSource().getServer());
                            return changed(ctx);
                        }))
                    .then(CommandManager.argument("x1", IntegerArgumentType.integer())
                        .then(CommandManager.argument("z1", IntegerArgumentType.integer())
                            .then(CommandManager.argument("x2", IntegerArgumentType.integer())
                                .then(CommandManager.argument("z2", IntegerArgumentType.integer())
                                    .executes(ctx -> {
                                        int x1 = IntegerArgumentType.getInteger(ctx, "x1"), z1 = IntegerArgumentType.getInteger(ctx, "z1");
                                        int x2 = IntegerArgumentType.getInteger(ctx, "x2"), z2 = IntegerArgumentType.getInteger(ctx, "z2");
                                        SpawnFilterConfig.setArea(new SpawnFilterConfig.Area(Math.min(x1, x2), Math.min(z1, z2),
                                            Math.max(x1, x2), Math.max(z1, z2)));
                                        ZoneView.broadcast(ctx.getSource().getServer());
                                        return changed(ctx);
                                    }))))))
                .then(CommandManager.literal("outside")
                    .then(CommandManager.argument("list", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            builder.suggest("all");
                            Region.ids().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            Set<String> unknown = SpawnFilterConfig.setOutsideRegions(StringArgumentType.getString(ctx, "list").replace(' ', ','));

                            if (!unknown.isEmpty()) {
                                ctx.getSource().sendError(Text.literal("[Spawn Filter] unknown region(s): " + unknown));
                                return 0;
                            }

                            return changed(ctx);
                        })))
                .then(CommandManager.literal("allow")
                    .executes(ctx -> {
                        SpawnFilterConfig.setAllow("");
                        return changed(ctx);
                    })
                    .then(CommandManager.argument("species", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            SpawnFilterConfig.setAllow(StringArgumentType.getString(ctx, "species").replace(' ', ','));
                            return changed(ctx);
                        })))
                .then(CommandManager.literal("deny")
                    .executes(ctx -> {
                        SpawnFilterConfig.setDeny("");
                        return changed(ctx);
                    })
                    .then(CommandManager.argument("species", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            SpawnFilterConfig.setDeny(StringArgumentType.getString(ctx, "species").replace(' ', ','));
                            return changed(ctx);
                        })))
                .then(CommandManager.literal("apply")
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        source.getServer().getCommandManager().executeWithPrefix(source, "reload");
                        return 1;
                    }))));
    }

    private static int show(CommandContext<ServerCommandSource> ctx) {
        String regions = SpawnFilterConfig.regions().stream()
            .map(r -> r.id() + "(" + r.range() + ")").collect(Collectors.joining(", "))
            + (SpawnFilterConfig.area() == null ? "" : " inside " + SpawnFilterConfig.area() + ", outside="
                + SpawnFilterConfig.outsideRegions().stream().map(Region::id).collect(Collectors.joining(",")));
        ctx.getSource().sendFeedback(() -> Text.literal("[Spawn Filter] enabled=" + SpawnFilterConfig.enabled()
            + ", regions=" + regions + ", allow=" + SpawnFilterConfig.allow() + ", deny=" + SpawnFilterConfig.deny()
            + ", removeExisting=" + SpawnFilterConfig.removeExisting()
            + ", dimensionPools=" + SpawnFilterConfig.dimensionPools()
            + "\nlast load: " + lastSummary
            + "\nexisting wild pokemon removed since start: " + removedExisting.get()), false);
        return 1;
    }

    private static int changed(CommandContext<ServerCommandSource> ctx) {
        show(ctx);
        ctx.getSource().sendFeedback(() -> Text.literal("[Spawn Filter] saved, run /spawnfilter apply to reload the spawns")
            .formatted(Formatting.YELLOW), false);
        return 1;
    }

    /** Called by the mixin before a spawn pool builds its details from the loaded spawn sets. */
    public static void filter(String pool, Map<Identifier, SpawnSet> sets) {
        INSIDE_CACHE.clear();
        OUTSIDE_CACHE.clear();

        if (!SpawnFilterConfig.enabled()) {
            lastSummary = "disabled, nothing filtered";
            LOGGER.info("[{}] {}", pool, lastSummary);
            return;
        }

        // with an area, entries are only dropped here if they may spawn neither inside nor outside of it,
        // the rest is decided per position when spawning (see filterCandidates)
        Set<Region> loadRegions = new HashSet<>(SpawnFilterConfig.regions());

        if (SpawnFilterConfig.area() != null) {
            loadRegions.addAll(SpawnFilterConfig.outsideRegions());
        }

        int kept = 0;
        int removed = 0;
        int unknown = 0;

        for (SpawnSet set : sets.values()) {
            List<SpawnDetail> filtered = new ArrayList<>();

            for (SpawnDetail detail : set.getSpawns()) {
                if (poolPrefixOf(detail) != null) {
                    filtered.add(detail); // curated spawns of an imported region map
                    kept++;
                } else if (detail instanceof PokemonSpawnDetail single) {
                    Boolean ok = allows(single.getPokemon(), loadRegions);

                    if (ok == null) {
                        unknown++;
                    }

                    if (ok == null || ok || SpawnRates.allForced().contains(SpawnRates.speciesOf(single.getPokemon()))) {
                        filtered.add(detail);
                        kept++;
                    } else {
                        removed++;
                    }
                } else if (detail instanceof PokemonHerdSpawnDetail herd) {
                    List<PokemonHerdSpawnDetail.Herdable> members = new ArrayList<>();

                    for (PokemonHerdSpawnDetail.Herdable member : herd.getHerdablePokemon()) {
                        Boolean ok = allows(member.getPokemon(), loadRegions);

                        if (ok == null || ok || SpawnRates.allForced().contains(SpawnRates.speciesOf(member.getPokemon()))) {
                            members.add(member);
                        }
                    }

                    if (members.isEmpty()) {
                        removed++;
                    } else {
                        herd.setHerdablePokemon(members);
                        filtered.add(detail);
                        kept++;
                    }
                } else {
                    filtered.add(detail); // npcs and other entities are not filtered
                }
            }

            set.setSpawns(filtered);
        }

        lastSummary = describe() + ": kept " + kept + ", removed " + removed + " pokemon spawn entries"
            + (unknown > 0 ? " (" + unknown + " with unknown species kept)" : "");
        LOGGER.info("[{}] {}", pool, lastSummary);
    }

    static String describe() {
        String inside = SpawnFilterConfig.regions().stream().map(Region::id).collect(Collectors.joining(","));

        if (SpawnFilterConfig.area() == null) {
            return "regions=" + inside;
        }

        return "inside area " + SpawnFilterConfig.area() + " regions=" + inside + ", outside regions="
            + SpawnFilterConfig.outsideRegions().stream().map(Region::id).collect(Collectors.joining(","));
    }

    /** The configured prefix the spawn entry belongs to, or null. */
    static String poolPrefixOf(SpawnDetail detail) {
        String id = detail.getId();

        for (String prefix : SpawnFilterConfig.dimensionPools().values()) {
            if (id != null && id.startsWith(prefix)) {
                return prefix;
            }
        }

        return null;
    }

    private static final Map<SpawnDetail, Boolean> INSIDE_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<SpawnDetail, Boolean> OUTSIDE_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Whether a position is inside the configured area (overworld only); without an area everything is inside. */
    static boolean inside(ServerWorld world, int x, int z) {
        SpawnFilterConfig.Area area = SpawnFilterConfig.area();
        return area == null || world.getRegistryKey() == World.OVERWORLD && area.contains(x, z);
    }

    /** Spawn candidates for a position: area rules, and imported region dimensions only get their own pool. */
    public static void filterCandidates(ServerWorld world, BlockPos pos, List<SpawnDetail> candidates) {
        if (!SpawnFilterConfig.enabled()) {
            return;
        }

        String dimension = world.getRegistryKey().getValue().toString();
        String own = SpawnFilterConfig.dimensionPools().get(dimension);

        if (!SpawnFilterConfig.dimensionPools().isEmpty()) {
            candidates.removeIf(detail -> {
                String prefix = poolPrefixOf(detail);
                return own != null ? !own.equals(prefix) : prefix != null;
            });
        }

        if (own != null || SpawnFilterConfig.area() == null && SpawnRates.allForced().isEmpty()) {
            return; // no area: everything was already filtered when loading (except forced species)
        }

        boolean in = inside(world, pos.getX(), pos.getZ());
        Set<Region> regions = in ? SpawnFilterConfig.regions() : SpawnFilterConfig.outsideRegions();
        Map<SpawnDetail, Boolean> cache = in ? INSIDE_CACHE : OUTSIDE_CACHE;
        candidates.removeIf(detail -> !cache.computeIfAbsent(detail, d -> mayAppear(d, regions))
            && !SpawnRates.isForcedAt(world, pos, detail));
    }

    private static boolean mayAppear(SpawnDetail detail, Set<Region> regions) {
        if (detail instanceof PokemonSpawnDetail single) {
            Boolean ok = allows(single.getPokemon(), regions);
            return ok == null || ok;
        }

        if (detail instanceof PokemonHerdSpawnDetail herd) {
            for (PokemonHerdSpawnDetail.Herdable member : herd.getHerdablePokemon()) {
                Boolean ok = allows(member.getPokemon(), regions);

                if (ok == null || ok) {
                    return true;
                }
            }

            return false;
        }

        return true;
    }

    /** @return whether the pokemon may spawn in the given regions, or null if its species is unknown */
    static Boolean allows(PokemonProperties properties, Set<Region> regions) {
        String name = properties.getSpecies();

        if (name == null) {
            return null;
        }

        String id = name.toLowerCase(Locale.ROOT);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        Species species = PokemonSpecies.getByName(path);

        if (species == null && !SpawnFilterConfig.allow().contains(path) && !SpawnFilterConfig.deny().contains(path)) {
            return null;
        }

        return allowed(path, species != null ? species.getNationalPokedexNumber() : 0, properties.getAspects(),
            properties.getForm(), regions);
    }

    static boolean allowed(Pokemon pokemon, Set<Region> regions) {
        return allowed(pokemon.getSpecies().getResourceIdentifier().getPath(), pokemon.getSpecies().getNationalPokedexNumber(),
            pokemon.getAspects(), pokemon.getForm().getName(), regions);
    }

    private static boolean allowed(String species, int dex, Iterable<String> aspects, String form, Set<Region> regions) {
        if (SpawnFilterConfig.deny().contains(species)) {
            return false;
        }

        if (SpawnFilterConfig.allow().contains(species)) {
            return true;
        }

        Region region = null;

        for (String aspect : aspects) {
            region = Region.byFormAspect(aspect);

            if (region != null) {
                break;
            }
        }

        if (region == null && form != null) {
            region = Region.byFormAspect(form);
        }

        if (region == null) {
            region = Region.byDex(dex);
        }

        return region != null && regions.contains(region);
    }

    /** Wild pokemon outside the regions that were saved in the world before (e.g. part of the downloaded map). */
    private static void onEntityLoad(Entity entity, ServerWorld world) {
        if (!SpawnFilterConfig.enabled() || !SpawnFilterConfig.removeExisting() || !shouldRemove(entity, world)) {
            return;
        }

        // discard on the next tick, not while the chunk is still adding its entities
        world.getServer().execute(() -> {
            if (!entity.isRemoved() && shouldRemove(entity, world)) {
                entity.discard();
                removedExisting.incrementAndGet();
            }
        });
    }

    /**
     * Whether a wild pokemon does not belong where it is with the current settings. Owned, battling, busy and
     * persistent pokemon (e.g. legendaries placed by the legends datapack) are never removed, nor pokemon that
     * appeared while the server ran (outbreaks, event legendaries, ...): only those already saved in the map.
     */
    static boolean shouldRemove(Entity entity, ServerWorld world) {
        if (!(entity instanceof PokemonEntity pokemonEntity) || entity.getCommandTags().contains(SPAWNED_TAG)) {
            return false;
        }

        if (SpawnFilterConfig.dimensionPools().containsKey(world.getRegistryKey().getValue().toString())) {
            return false; // imported region maps keep their own pokemon
        }

        Pokemon pokemon = pokemonEntity.getPokemon();

        if (!pokemon.isWild() || pokemon.getOwnerUUID() != null || pokemonEntity.isBattling() || pokemonEntity.isBusy()
            || pokemonEntity.isPersistent()) {
            return false;
        }

        Set<Region> regions = inside(world, entity.getBlockX(), entity.getBlockZ())
            ? SpawnFilterConfig.regions() : SpawnFilterConfig.outsideRegions();
        return !allowed(pokemon, regions);
    }

    /** Removes the wild pokemon in all loaded chunks that do not match the current settings, returns how many. */
    public static int cleanupLoaded(MinecraftServer server) {
        if (!SpawnFilterConfig.enabled()) {
            return 0;
        }

        int removed = 0;

        for (ServerWorld world : server.getWorlds()) {
            List<Entity> remove = new ArrayList<>();

            for (Entity entity : world.iterateEntities()) {
                if (shouldRemove(entity, world)) {
                    remove.add(entity);
                }
            }

            remove.forEach(Entity::discard);
            removed += remove.size();
        }

        removedExisting.addAndGet(removed);
        LOGGER.info("cleanup: removed {} wild pokemon that do not match {}", removed, describe());
        return removed;
    }

    static int openRates(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, RatesPayloads.Snapshot.ID)) {
            player.sendMessage(Text.literal("[스폰 필터] 확률 설정 화면은 클라이언트에도 Spawn Filter 모드가 있어야 열립니다.")
                .formatted(Formatting.RED));
            return 0;
        }

        List<RatesPayloads.ZoneData> zones = new ArrayList<>();

        for (SpawnRates.Zone z : SpawnRates.zones()) {
            zones.add(new RatesPayloads.ZoneData(z.name, z.area, z.dimension, z.minX, z.minZ, z.maxX, z.maxZ, z.multipliers,
                List.copyOf(z.forced)));
        }

        ServerPlayNetworking.send(player, new RatesPayloads.Snapshot(SpawnRates.currentBuckets(), SpawnRates.defaultBuckets(), zones));
        return 1;
    }

    private static void applyRates(ServerPlayerEntity player, RatesPayloads.Apply payload) {
        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("[스폰 필터] 권한이 없습니다 (OP 필요).").formatted(Formatting.RED));
            return;
        }

        Set<String> known = new HashSet<>();
        PokemonSpecies.getSpecies().forEach(s -> known.add(s.getResourceIdentifier().getPath()));
        List<SpawnRates.Zone> zones = new ArrayList<>();

        for (RatesPayloads.ZoneData d : payload.zones()) {
            SpawnRates.Zone z = new SpawnRates.Zone();
            z.name = d.name();
            z.area = d.area();
            z.dimension = d.dimension();
            z.minX = d.minX();
            z.minZ = d.minZ();
            z.maxX = d.maxX();
            z.maxZ = d.maxZ();
            z.multipliers.putAll(d.multipliers());
            z.forced.addAll(d.forced());
            zones.add(z);
        }

        Map<String, Float> buckets = new LinkedHashMap<>();
        Map<String, Float> current = SpawnRates.currentBuckets();
        payload.buckets().forEach((name, weight) -> {
            if (current.containsKey(name)) {
                buckets.put(name, weight);
            }
        });

        SpawnRates.set(buckets, zones, known);
        LOGGER.info("{} changed spawn rates: buckets={}, zones={}", player.getGameProfile().getName(),
            SpawnRates.currentBuckets(), SpawnRates.zones().size() - 1);
        player.sendMessage(Text.literal("[스폰 필터] 스폰 확률을 적용했습니다. (바로 적용됨)").formatted(Formatting.GREEN));
        ZoneView.broadcast(player.getServer());

        List<String> noData = loadForced(player.getServer(), () ->
            player.sendMessage(Text.literal("[스폰 필터] 강제 스폰 포켓몬을 불러오려고 데이터를 다시 읽습니다 (잠깐 렉)").formatted(Formatting.YELLOW)));

        if (!noData.isEmpty()) {
            player.sendMessage(Text.literal("[스폰 필터] 자연 스폰 정보가 없어 강제 스폰할 수 없는 포켓몬: " + String.join(", ", noData))
                .formatted(Formatting.RED));
        }

        openRates(player);
    }

    /**
     * The region filter dropped the spawn entries of some newly forced species when loading: reload the data to get
     * them (beforeReload runs first, e.g. to warn about the lag). Returns the forced species that have no natural
     * spawn entries at all and so cannot be forced.
     */
    public static List<String> loadForced(MinecraftServer server, Runnable beforeReload) {
        List<String> noData = SpawnRates.allForced().stream().filter(s -> !SpawnRates.hasSpawnData(s)).sorted().toList();
        List<String> missing = SpawnRates.allForced().stream()
            .filter(s -> SpawnRates.hasSpawnData(s) && !SpawnRates.inPools(s)).sorted().toList();

        if (!missing.isEmpty()) {
            LOGGER.info("forced species without loaded spawn entries {}, reloading", missing);
            beforeReload.run();
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), "reload");
        }

        return noData;
    }
}
