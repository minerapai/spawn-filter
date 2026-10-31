package kr.spawnfilter.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Network payloads of the spawn rate screen. Only clients with the mod get them. */
public final class RatesPayloads {
    private RatesPayloads() {
    }

    /** One zone as sent over the network (area == false: the whole server). */
    public record ZoneData(String name, boolean area, String dimension, int minX, int minZ, int maxX, int maxZ,
                           Map<String, Float> multipliers, List<String> forced) {
        void write(RegistryByteBuf buf) {
            buf.writeString(name);
            buf.writeBoolean(area);
            buf.writeString(dimension);
            buf.writeInt(minX);
            buf.writeInt(minZ);
            buf.writeInt(maxX);
            buf.writeInt(maxZ);
            writeMap(buf, multipliers);
            buf.writeVarInt(forced.size());
            forced.forEach(buf::writeString);
        }

        static ZoneData read(RegistryByteBuf buf) {
            return new ZoneData(buf.readString(), buf.readBoolean(), buf.readString(), buf.readInt(), buf.readInt(),
                buf.readInt(), buf.readInt(), readMap(buf), readStrings(buf));
        }

        private static List<String> readStrings(RegistryByteBuf buf) {
            int size = buf.readVarInt();
            List<String> list = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                list.add(buf.readString());
            }

            return list;
        }
    }

    static void writeMap(RegistryByteBuf buf, Map<String, Float> map) {
        buf.writeVarInt(map.size());
        map.forEach((k, v) -> {
            buf.writeString(k);
            buf.writeFloat(v);
        });
    }

    static Map<String, Float> readMap(RegistryByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, Float> map = new LinkedHashMap<>();

        for (int i = 0; i < size; i++) {
            map.put(buf.readString(), buf.readFloat());
        }

        return map;
    }

    static void writeZones(RegistryByteBuf buf, List<ZoneData> zones) {
        buf.writeVarInt(zones.size());
        zones.forEach(z -> z.write(buf));
    }

    static List<ZoneData> readZones(RegistryByteBuf buf) {
        int size = buf.readVarInt();
        List<ZoneData> zones = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            zones.add(ZoneData.read(buf));
        }

        return zones;
    }

    /** Server -> client: current values, opens the screen. */
    public record Snapshot(Map<String, Float> buckets, Map<String, Float> defaultBuckets,
                           List<ZoneData> zones) implements CustomPayload {
        public static final Id<Snapshot> ID = new Id<>(Identifier.of("spawnfilter", "rates_snapshot"));
        public static final PacketCodec<RegistryByteBuf, Snapshot> CODEC = PacketCodec.of(
            (value, buf) -> {
                writeMap(buf, value.buckets());
                writeMap(buf, value.defaultBuckets());
                writeZones(buf, value.zones());
            },
            buf -> new Snapshot(readMap(buf), readMap(buf), readZones(buf)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: start (true) or stop showing the zones in the world. */
    public record ViewRequest(boolean show) implements CustomPayload {
        public static final Id<ViewRequest> ID = new Id<>(Identifier.of("spawnfilter", "view_request"));
        public static final PacketCodec<RegistryByteBuf, ViewRequest> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeBoolean(value.show()),
            buf -> new ViewRequest(buf.readBoolean()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> client: zones to draw; filterArea is the region filter boundary (overworld), null if none. */
    public record View(List<ZoneData> zones, ZoneData filterArea) implements CustomPayload {
        public static final Id<View> ID = new Id<>(Identifier.of("spawnfilter", "view"));
        public static final PacketCodec<RegistryByteBuf, View> CODEC = PacketCodec.of(
            (value, buf) -> {
                writeZones(buf, value.zones());
                buf.writeBoolean(value.filterArea() != null);

                if (value.filterArea() != null) {
                    value.filterArea().write(buf);
                }
            },
            buf -> new View(readZones(buf), buf.readBoolean() ? ZoneData.read(buf) : null));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: the values to apply. */
    public record Apply(Map<String, Float> buckets, List<ZoneData> zones) implements CustomPayload {
        public static final Id<Apply> ID = new Id<>(Identifier.of("spawnfilter", "rates_apply"));
        public static final PacketCodec<RegistryByteBuf, Apply> CODEC = PacketCodec.of(
            (value, buf) -> {
                writeMap(buf, value.buckets());
                writeZones(buf, value.zones());
            },
            buf -> new Apply(readMap(buf), readZones(buf)));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
