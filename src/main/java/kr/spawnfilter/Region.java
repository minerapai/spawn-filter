package kr.spawnfilter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Pokemon regions by national dex number, regional forms are matched by their aspect. */
public enum Region {
    KANTO("kanto", 1, 151, null),
    JOHTO("johto", 152, 251, null),
    HOENN("hoenn", 252, 386, null),
    SINNOH("sinnoh", 387, 493, null),
    UNOVA("unova", 494, 649, null),
    KALOS("kalos", 650, 721, null),
    ALOLA("alola", 722, 809, "alolan"),
    GALAR("galar", 810, 898, "galarian"),
    HISUI("hisui", 899, 905, "hisuian"),
    PALDEA("paldea", 906, 1025, "paldean");

    private final String id;
    private final int firstDex;
    private final int lastDex;
    private final String formAspect;

    Region(String id, int firstDex, int lastDex, String formAspect) {
        this.id = id;
        this.firstDex = firstDex;
        this.lastDex = lastDex;
        this.formAspect = formAspect;
    }

    public String id() {
        return id;
    }

    public String range() {
        return firstDex + "-" + lastDex;
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(Region::id).toList();
    }

    public static Region byId(String id) {
        for (Region region : values()) {
            if (region.id.equals(id.toLowerCase(Locale.ROOT))) {
                return region;
            }
        }

        return null;
    }

    public static Region byDex(int dex) {
        for (Region region : values()) {
            if (dex >= region.firstDex && dex <= region.lastDex) {
                return region;
            }
        }

        return null;
    }

    /** The region of a regional form aspect such as "alolan", or null. */
    public static Region byFormAspect(String aspect) {
        String a = aspect.toLowerCase(Locale.ROOT);

        for (Region region : values()) {
            // "alolan" as written in spawn files, "alola" as a form name
            if (region.formAspect != null && (a.equals(region.formAspect) || a.equals(region.id))) {
                return region;
            }
        }

        return null;
    }
}
