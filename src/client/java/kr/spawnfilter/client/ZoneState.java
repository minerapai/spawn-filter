package kr.spawnfilter.client;

import kr.spawnfilter.net.RatesPayloads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Unapplied edits of one zone. Numbers are kept as text so half typed values survive screen changes. */
final class ZoneState {
    String name;
    boolean area;
    String dimension;
    String x1, z1, x2, z2;
    /** {species, multiplier text, "1" if forced} */
    final List<String[]> species = new ArrayList<>();

    static ZoneState of(RatesPayloads.ZoneData d) {
        ZoneState z = new ZoneState();
        z.name = d.name();
        z.area = d.area();
        z.dimension = d.dimension();
        z.x1 = Integer.toString(d.minX());
        z.z1 = Integer.toString(d.minZ());
        z.x2 = Integer.toString(d.maxX());
        z.z2 = Integer.toString(d.maxZ());
        d.multipliers().forEach((s, m) -> z.species.add(new String[]{s, RatesScreen.format(m), d.forced().contains(s) ? "1" : "0"}));
        return z;
    }

    static ZoneState newArea(String dimension, int x, int z) {
        ZoneState s = new ZoneState();
        s.name = "새 구역";
        s.area = true;
        s.dimension = dimension;
        s.x1 = Integer.toString(x - 50);
        s.z1 = Integer.toString(z - 50);
        s.x2 = Integer.toString(x + 50);
        s.z2 = Integer.toString(z + 50);
        return s;
    }

    ZoneState copy() {
        ZoneState c = new ZoneState();
        c.name = name;
        c.area = area;
        c.dimension = dimension;
        c.x1 = x1;
        c.z1 = z1;
        c.x2 = x2;
        c.z2 = z2;
        species.forEach(row -> c.species.add(row.clone()));
        return c;
    }

    static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    boolean coordsValid() {
        return !area || parseInt(x1) != null && parseInt(z1) != null && parseInt(x2) != null && parseInt(z2) != null;
    }

    boolean valid() {
        if (!coordsValid()) {
            return false;
        }

        for (String[] row : species) {
            if (RatesScreen.parse(row[1], RatesScreen.MAX_MULT) == null) {
                return false;
            }
        }

        return true;
    }

    RatesPayloads.ZoneData toData() {
        Map<String, Float> m = new LinkedHashMap<>();
        List<String> forced = new ArrayList<>();
        species.forEach(row -> {
            String id = row[0].toLowerCase(Locale.ROOT);
            m.put(id, RatesScreen.parse(row[1], RatesScreen.MAX_MULT));

            if (row[2].equals("1")) {
                forced.add(id);
            }
        });
        int ax = area ? parseInt(x1) : 0, az = area ? parseInt(z1) : 0, bx = area ? parseInt(x2) : 0, bz = area ? parseInt(z2) : 0;
        return new RatesPayloads.ZoneData(name, area, dimension, Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx),
            Math.max(az, bz), m, forced);
    }

    String summary() {
        if (!area) {
            return "서버 전체";
        }

        String dim = dimension.equals("minecraft:overworld") ? "" : dimension + " ";
        return dim + "(" + x1 + ", " + z1 + ") ~ (" + x2 + ", " + z2 + ")";
    }
}
