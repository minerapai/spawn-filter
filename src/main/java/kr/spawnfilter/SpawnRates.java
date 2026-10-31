package kr.spawnfilter;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.spawning.BestSpawner;
import com.cobblemon.mod.common.api.spawning.SpawnBucket;
import com.cobblemon.mod.common.api.spawning.detail.PokemonHerdSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnPool;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import com.cobblemon.mod.common.api.spawning.SpawnSet;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spawn rates: the weights of Cobblemon's rarity buckets (common, uncommon, ...) and spawn weight multipliers per
 * species, for the whole server and for named zones. Cobblemon asks for the weight of every spawn candidate at
 * its position (SpawnablePositionMixin), so all of this applies live. Saved to config/spawn-rates.json.
 */
public final class SpawnRates {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("spawn-rates.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final float MAX_BUCKET_WEIGHT = 10000f;
    public static final float MAX_MULTIPLIER = 100f;
    public static final String OVERWORLD = "minecraft:overworld";

    /** A rectangle (block coordinates, inclusive) in one dimension; area == false means the whole server. */
    public static final class Zone {
        public String name = "";
        public boolean area = true;
        public String dimension = OVERWORLD;
        public int minX, minZ, maxX, maxZ;
        public Map<String, Float> multipliers = new LinkedHashMap<>();
        /** species that spawn in this zone even if their biome/time conditions or the region filter say no */
        public Set<String> forced = new LinkedHashSet<>();

        boolean contains(String dim, int x, int z) {
            return !area || dimension.equals(dim) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static final class Data {
        Map<String, Float> buckets = new LinkedHashMap<>();
        List<Zone> zones = new ArrayList<>();
    }

    /** Cobblemon's own bucket weights, captured before anything is changed. */
    private static final Map<String, Float> DEFAULT_BUCKETS = new LinkedHashMap<>();
    private static volatile Data data = new Data();
    /** species of each spawn entry (single: one, herd: all members), cached since weights are asked very often */
    private static final Map<SpawnDetail, List<String>> SPECIES_CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    /** spawn entries of every pool by species, to add forced species where their conditions would not let them */
    private static final Map<SpawnPool, Map<String, List<SpawnDetail>>> POOL_INDEX = Collections.synchronizedMap(new IdentityHashMap<>());
    /** species that have spawn entries in the spawn data */
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    /** all forced species of all zones and server events */
    private static volatile Set<String> allForced = Set.of();
    /** spawn boosts of running server events (see setEventBoosts) */
    private static volatile Map<String, Float> eventMultipliers = Map.of();

    static {
        load();
    }

    private SpawnRates() {
    }

    public static synchronized Map<String, Float> defaultBuckets() {
        captureDefaults();
        return Map.copyOf(DEFAULT_BUCKETS);
    }

    public static synchronized Map<String, Float> currentBuckets() {
        Map<String, Float> current = new LinkedHashMap<>();

        for (SpawnBucket bucket : BestSpawner.INSTANCE.getConfig().getBuckets()) {
            current.put(bucket.getName(), bucket.getWeight());
        }

        return current;
    }

    /** The configured zones, the first one is always the whole server. */
    public static List<Zone> zones() {
        return data.zones;
    }

    private static void captureDefaults() {
        if (DEFAULT_BUCKETS.isEmpty()) {
            for (SpawnBucket bucket : BestSpawner.INSTANCE.getConfig().getBuckets()) {
                DEFAULT_BUCKETS.put(bucket.getName(), bucket.getWeight());
            }
        }
    }

    /** Applies the configured bucket weights to Cobblemon (call after Cobblemon loaded its spawner config). */
    public static synchronized void applyBuckets() {
        captureDefaults();

        for (SpawnBucket bucket : BestSpawner.INSTANCE.getConfig().getBuckets()) {
            Float weight = data.buckets.get(bucket.getName());
            bucket.setWeight(weight != null ? weight : DEFAULT_BUCKETS.getOrDefault(bucket.getName(), bucket.getWeight()));
        }

        SpawnFilter.LOGGER.info("spawn rates: buckets={}, zones={}", currentBuckets(), data.zones.size() - 1);
    }

    /** Spawn entries are rebuilt on every data reload. */
    public static void onSpawnSetsLoaded(Collection<SpawnSet> sets) {
        SPECIES_CACHE.clear();

        for (SpawnSet set : sets) {
            for (SpawnDetail detail : set.getSpawns()) {
                SEEN.addAll(speciesOf(detail));
            }
        }
    }

    /** Whether the spawn data has natural spawn entries for a species at all (before the region filter). */
    public static boolean hasSpawnData(String species) {
        return SEEN.contains(species);
    }

    /** Indexes the spawn entries of a pool once it built them. */
    public static void onPoolLoaded(SpawnPool pool) {
        Map<String, List<SpawnDetail>> index = new HashMap<>();

        for (SpawnDetail detail : pool.getDetails()) {
            for (String species : speciesOfCached(detail)) {
                index.computeIfAbsent(species, k -> new ArrayList<>()).add(detail);
            }
        }

        POOL_INDEX.put(pool, index);
    }

    private static List<String> speciesOfCached(SpawnDetail detail) {
        return SPECIES_CACHE.computeIfAbsent(detail, SpawnRates::speciesOf);
    }

    /** Species forced in any zone (the region filter keeps their spawn entries when loading). */
    public static Set<String> allForced() {
        return allForced;
    }

    /** Whether any loaded pool has spawn entries for a species. */
    public static boolean inPools(String species) {
        synchronized (POOL_INDEX) {
            for (Map<String, List<SpawnDetail>> index : POOL_INDEX.values()) {
                if (index.containsKey(species)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** The species forced at a position (all zones that contain it). */
    private static Set<String> forcedAt(ServerWorld world, BlockPos pos) {
        Set<String> result = eventMultipliers.isEmpty() ? null : new HashSet<>(eventMultipliers.keySet());
        String dimension = null;

        for (Zone zone : data.zones) {
            if (zone.forced.isEmpty()) {
                continue;
            }

            if (zone.area) {
                if (dimension == null) {
                    dimension = world.getRegistryKey().getValue().toString();
                }

                if (!zone.contains(dimension, pos.getX(), pos.getZ())) {
                    continue;
                }
            }

            if (result == null) {
                result = new HashSet<>();
            }

            result.addAll(zone.forced);
        }

        return result == null ? Set.of() : result;
    }

    /** Whether a spawn entry is forced at a position (any of its species). */
    public static boolean isForcedAt(ServerWorld world, BlockPos pos, SpawnDetail detail) {
        if (allForced.isEmpty()) {
            return false;
        }

        Set<String> forced = forcedAt(world, pos);

        if (forced.isEmpty()) {
            return false;
        }

        for (String species : speciesOfCached(detail)) {
            if (forced.contains(species)) {
                return true;
            }
        }

        return false;
    }

    /** Adds the entries of forced species that fit the bucket and the kind of position (ground, water, ...). */
    public static boolean addForced(SpawnPool pool, SpawnBucket bucket, SpawnablePosition position, List<SpawnDetail> candidates) {
        if (allForced.isEmpty()) {
            return false;
        }

        Set<String> forced = forcedAt(position.getWorld(), position.getPosition());
        Map<String, List<SpawnDetail>> index = POOL_INDEX.get(pool);

        if (forced.isEmpty() || index == null) {
            return false;
        }

        boolean added = false;

        for (String species : forced) {
            for (SpawnDetail detail : index.getOrDefault(species, List.of())) {
                if (detail.getBucket().getName().equals(bucket.getName())
                    && detail.getSpawnablePositionType().getClazz() == position.getClass()
                    && candidates.stream().noneMatch(c -> c == detail)) {
                    candidates.add(detail);
                    added = true;
                }
            }
        }

        return added;
    }

    /** The multiplier for a spawn candidate at a position: product of all zones that contain it. */
    public static float multiplierAt(ServerWorld world, BlockPos pos, SpawnDetail detail) {
        List<Zone> zones = data.zones;
        float result = 1f;
        List<String> species = null;
        String dimension = null;
        Map<String, Float> event = eventMultipliers;

        if (!event.isEmpty()) {
            species = speciesOfCached(detail);
            float best = -1f;

            for (String s : species) {
                Float m = event.get(s);

                if (m != null) {
                    best = Math.max(best, m);
                }
            }

            if (best >= 0) {
                result *= best;
            }
        }

        for (Zone zone : zones) {
            if (zone.multipliers.isEmpty()) {
                continue;
            }

            if (zone.area) {
                if (dimension == null) {
                    dimension = world.getRegistryKey().getValue().toString();
                }

                if (!zone.contains(dimension, pos.getX(), pos.getZ())) {
                    continue;
                }
            }

            if (species == null) {
                species = speciesOfCached(detail);
            }

            float best = -1f; // herds: the highest configured multiplier of its members

            for (String s : species) {
                Float m = zone.multipliers.get(s);

                if (m != null) {
                    best = Math.max(best, m);
                }
            }

            if (best >= 0) {
                result *= best;
            }
        }

        return result;
    }

    private static List<String> speciesOf(SpawnDetail detail) {
        if (detail instanceof PokemonSpawnDetail single) {
            String s = speciesOf(single.getPokemon());
            return s == null ? List.of() : List.of(s);
        }

        if (detail instanceof PokemonHerdSpawnDetail herd) {
            List<String> list = new ArrayList<>();

            for (PokemonHerdSpawnDetail.Herdable member : herd.getHerdablePokemon()) {
                String s = speciesOf(member.getPokemon());

                if (s != null) {
                    list.add(s);
                }
            }

            return list;
        }

        return List.of();
    }

    public static String speciesOf(PokemonProperties properties) {
        String name = properties.getSpecies();

        if (name == null) {
            return null;
        }

        String id = name.toLowerCase(Locale.ROOT);
        return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
    }

    /** Sets everything from the rates screen, applies it live and saves. */
    public static synchronized void set(Map<String, Float> buckets, List<Zone> zones, Set<String> knownSpecies) {
        Data d = new Data();
        buckets.forEach((name, weight) -> d.buckets.put(name, clamp(weight, MAX_BUCKET_WEIGHT)));

        for (Zone zone : zones) {
            Zone z = new Zone();
            z.name = zone.name == null ? "" : zone.name.trim();
            z.area = zone.area;
            z.dimension = zone.dimension == null || zone.dimension.isBlank() ? OVERWORLD : zone.dimension;
            z.minX = Math.min(zone.minX, zone.maxX);
            z.maxX = Math.max(zone.minX, zone.maxX);
            z.minZ = Math.min(zone.minZ, zone.maxZ);
            z.maxZ = Math.max(zone.minZ, zone.maxZ);
            zone.multipliers.forEach((species, mult) -> {
                String id = species.toLowerCase(Locale.ROOT);

                if (knownSpecies.contains(id)) {
                    z.multipliers.put(id, clamp(mult, MAX_MULTIPLIER));
                }
            });
            zone.forced.forEach(species -> {
                String id = species.toLowerCase(Locale.ROOT);

                if (z.multipliers.containsKey(id)) {
                    z.forced.add(id);
                }
            });
            d.zones.add(z);
        }

        ensureGlobal(d);
        data = d;
        updateForced();
        applyBuckets();
        save();
    }

    /** The first zone is always the whole server. */
    private static void ensureGlobal(Data d) {
        if (d.zones.isEmpty() || d.zones.get(0).area) {
            Zone global = new Zone();
            global.name = "전체 서버";
            global.area = false;
            d.zones.add(0, global);
        }
    }

    private static float clamp(Float value, float max) {
        if (value == null || value.isNaN()) {
            return 1f;
        }

        return Math.max(0f, Math.min(max, value));
    }

    public static synchronized void load() {
        Data d = null;

        try {
            if (Files.exists(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    d = GSON.fromJson(r, new TypeToken<Data>() { }.getType());
                }
            }
        } catch (Exception e) {
            SpawnFilter.LOGGER.error("failed to read {}", FILE, e);
        }

        if (d == null) {
            d = new Data();
        }

        if (d.buckets == null) {
            d.buckets = new LinkedHashMap<>();
        }

        if (d.zones == null) {
            d.zones = new ArrayList<>();
        }

        d.zones.removeIf(z -> z == null);
        d.zones.forEach(z -> {
            if (z.multipliers == null) {
                z.multipliers = new LinkedHashMap<>();
            }

            if (z.forced == null) {
                z.forced = new LinkedHashSet<>();
            }
        });
        ensureGlobal(d);
        data = d;
        updateForced();
        save();
    }

    private static void updateForced() {
        Set<String> all = new HashSet<>(eventMultipliers.keySet());
        data.zones.forEach(z -> all.addAll(z.forced));
        allForced = Set.copyOf(all);
    }

    /**
     * Temporary spawn boosts of server events (another mod): each species spawns everywhere (forced, like a zone's
     * forced species) with its weight multiplied. Not saved; an empty map ends the event. Species are ids without
     * namespace ("dratini"). Call SpawnFilter.loadForced afterwards so species the region filter dropped are loaded.
     */
    public static synchronized void setEventBoosts(Map<String, Float> boosts) {
        Map<String, Float> clean = new HashMap<>();
        boosts.forEach((species, mult) -> clean.put(species.toLowerCase(Locale.ROOT), clamp(mult, MAX_MULTIPLIER)));
        eventMultipliers = Map.copyOf(clean);
        updateForced();
    }

    public static Map<String, Float> eventBoosts() {
        return eventMultipliers;
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            SpawnFilter.LOGGER.error("failed to write {}", FILE, e);
        }
    }
}
