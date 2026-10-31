package kr.spawnfilter;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public final class SpawnFilterConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("spawn-filter.properties");
    private static final String COMMENT = String.join(System.lineSeparator() + "# ",
        "Spawn Filter - only let pokemon of the chosen regions spawn naturally (also fishing / bait)",
        "enabled : true or false",
        "regions : comma separated: " + String.join(",", Region.ids()),
        "          (inside the area if an area is set, otherwise everywhere)",
        "area    : x1,z1,x2,z2 block corners of an overworld area (e.g. the kanto map), empty = whole world",
        "outsideRegions : regions that spawn outside the area (comma separated or 'all')",
        "          regional forms count for their own region, e.g. alolan vulpix is 'alola'",
        "allow   : comma separated species that may always spawn (e.g. pichu,crobat)",
        "deny    : comma separated species that may never spawn (e.g. mewtwo)",
        "dimensionPools : dimension=prefix pairs, e.g. johto:johto=johto_ : spawn entries whose id starts with the",
        "                 prefix only spawn in that dimension, are never region filtered, and that dimension",
        "                 only gets those entries (for imported region maps with their own spawn data)",
        "removeExisting : remove wild pokemon outside the regions that are already saved in the world",
        "                 (removed when their chunk loads; owned, battling or busy pokemon are never touched)",
        "Evolution, breeding, raids, trades etc. are not affected.",
        "In game (op level 2): /spawnfilter, then /spawnfilter apply (runs /reload) to apply changes");

    private static volatile boolean enabled = true;
    private static volatile Set<Region> regions = Set.of(Region.values()); // every region: nothing changes until set
    private static volatile Set<String> allow = Set.of();
    private static volatile Set<String> deny = Set.of();
    private static volatile boolean removeExisting = true;
    private static volatile Map<String, String> dimensionPools = Map.of();
    private static volatile Area area = null;
    private static volatile Set<Region> outsideRegions = Set.of(Region.values());

    /** An overworld rectangle in block coordinates (inclusive). */
    public record Area(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        @Override
        public String toString() {
            return "(" + minX + "," + minZ + ")~(" + maxX + "," + maxZ + ")";
        }

        String serialize() {
            return minX + "," + minZ + "," + maxX + "," + maxZ;
        }

        static Area parse(String text) {
            String[] p = text.replace(" ", "").split(",");

            if (p.length != 4) {
                return null;
            }

            int x1 = Integer.parseInt(p[0]), z1 = Integer.parseInt(p[1]), x2 = Integer.parseInt(p[2]), z2 = Integer.parseInt(p[3]);
            return new Area(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        }
    }

    public static Area area() {
        return area;
    }

    public static Set<Region> outsideRegions() {
        return outsideRegions;
    }

    public static synchronized void setArea(Area value) {
        area = value;
        save();
    }

    /** @return the unknown region ids, nothing is changed if there are any */
    public static synchronized Set<String> setOutsideRegions(String list) {
        Set<String> unknown = new LinkedHashSet<>();
        Set<Region> parsed = parseRegions(list, unknown);

        if (unknown.isEmpty()) {
            outsideRegions = Set.copyOf(parsed);
            save();
        }

        return unknown;
    }

    private static Set<Region> parseRegions(String list, Set<String> unknown) {
        Set<Region> parsed = new LinkedHashSet<>();

        for (String id : split(list)) {
            if (id.equals("all")) {
                parsed.addAll(Arrays.asList(Region.values()));
                continue;
            }

            Region region = Region.byId(id);

            if (region == null) {
                unknown.add(id);
            } else {
                parsed.add(region);
            }
        }

        return parsed;
    }

    static {
        load();
    }

    private SpawnFilterConfig() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static Set<Region> regions() {
        return regions;
    }

    public static Set<String> allow() {
        return allow;
    }

    public static Set<String> deny() {
        return deny;
    }

    /** dimension id -> spawn id prefix */
    public static Map<String, String> dimensionPools() {
        return dimensionPools;
    }

    public static boolean removeExisting() {
        return removeExisting;
    }

    public static synchronized void setRemoveExisting(boolean value) {
        removeExisting = value;
        save();
    }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    /** Used by the selection menu: sets everything it shows in one save. */
    public static synchronized void setSelection(boolean enabledValue, Set<Region> inside, Set<Region> outside,
                                                 boolean removeExistingValue) {
        enabled = enabledValue;
        removeExisting = removeExistingValue;
        regions = Set.copyOf(inside);
        outsideRegions = Set.copyOf(outside);
        save();
    }

    /** @return the unknown region ids, nothing is changed if there are any */
    public static synchronized Set<String> setRegions(String list) {
        Set<String> unknown = new LinkedHashSet<>();
        Set<Region> parsed = new LinkedHashSet<>();

        for (String id : split(list)) {
            Region region = Region.byId(id);

            if (region == null) {
                unknown.add(id);
            } else {
                parsed.add(region);
            }
        }

        if (unknown.isEmpty()) {
            regions = Set.copyOf(parsed);
            save();
        }

        return unknown;
    }

    public static synchronized void setAllow(String list) {
        allow = split(list);
        save();
    }

    public static synchronized void setDeny(String list) {
        deny = split(list);
        save();
    }

    public static synchronized void load() {
        Properties p = new Properties();

        try {
            if (Files.exists(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
        } catch (Exception e) {
            SpawnFilter.LOGGER.error("failed to read {}", FILE, e);
        }

        enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
        Set<Region> parsed = new LinkedHashSet<>();

        for (String id : split(p.getProperty("regions", String.join(",", Region.ids())))) {
            Region region = Region.byId(id);

            if (region != null) {
                parsed.add(region);
            } else {
                SpawnFilter.LOGGER.warn("unknown region '{}' in {}", id, FILE);
            }
        }

        regions = Set.copyOf(parsed);
        allow = split(p.getProperty("allow", ""));
        deny = split(p.getProperty("deny", ""));
        removeExisting = Boolean.parseBoolean(p.getProperty("removeExisting", "true").trim());
        Map<String, String> pools = new LinkedHashMap<>();

        for (String pair : p.getProperty("dimensionPools", "").split(",")) {
            int eq = pair.lastIndexOf('=');

            if (eq > 0 && eq < pair.length() - 1) {
                pools.put(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT), pair.substring(eq + 1).trim());
            } else if (!pair.isBlank()) {
                SpawnFilter.LOGGER.warn("invalid dimensionPools entry '{}' in {}", pair, FILE);
            }
        }

        dimensionPools = Map.copyOf(pools);

        try {
            String areaText = p.getProperty("area", "").trim();
            area = areaText.isEmpty() ? null : Area.parse(areaText);
        } catch (NumberFormatException e) {
            SpawnFilter.LOGGER.warn("invalid area in {}", FILE);
            area = null;
        }

        Set<String> unknownOutside = new LinkedHashSet<>();
        outsideRegions = Set.copyOf(parseRegions(p.getProperty("outsideRegions", "all"), unknownOutside));
        unknownOutside.forEach(id -> SpawnFilter.LOGGER.warn("unknown outside region '{}' in {}", id, FILE));
        save();
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("regions", Arrays.stream(Region.values()).filter(regions::contains)
            .map(Region::id).collect(Collectors.joining(",")));
        p.setProperty("allow", String.join(",", allow));
        p.setProperty("deny", String.join(",", deny));
        p.setProperty("removeExisting", Boolean.toString(removeExisting));
        p.setProperty("area", area == null ? "" : area.serialize());
        p.setProperty("outsideRegions", outsideRegions.size() == Region.values().length ? "all"
            : Arrays.stream(Region.values()).filter(outsideRegions::contains).map(Region::id).collect(Collectors.joining(",")));
        p.setProperty("dimensionPools", dimensionPools.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(",")));

        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(w, COMMENT);
        } catch (Exception e) {
            SpawnFilter.LOGGER.error("failed to write {}", FILE, e);
        }
    }

    private static Set<String> split(String list) {
        return Arrays.stream(list.split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
