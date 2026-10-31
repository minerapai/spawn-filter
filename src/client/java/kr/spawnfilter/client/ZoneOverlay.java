package kr.spawnfilter.client;

import kr.spawnfilter.net.RatesPayloads;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Draws the spawn rate zones and the region filter boundary as grid walls around the player. */
final class ZoneOverlay {
    /** how far from the player wall segments are drawn */
    private static final int RANGE = 96;
    private static final int STEP = 4;
    private static final int BELOW = 16;
    private static final int ABOVE = 24;
    private static final int[] COLORS = {0x55FFFF, 0xFFFF55, 0x55FF55, 0xFF55FF, 0x5599FF, 0xFFAA00, 0xFFFFFF};
    private static final int FILTER_COLOR = 0xFF5555;

    static boolean enabled;
    private static List<RatesPayloads.ZoneData> zones = List.of();
    private static RatesPayloads.ZoneData filterArea;

    private ZoneOverlay() {
    }

    static void update(RatesPayloads.View view) {
        zones = view.zones();
        filterArea = view.filterArea();
    }

    static void reset() {
        enabled = false;
        zones = List.of();
        filterArea = null;
    }

    private static int colorOf(int index) {
        return COLORS[index % COLORS.length];
    }

    static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!enabled || client.world == null || client.player == null || context.consumers() == null) {
            return;
        }

        String dimension = client.world.getRegistryKey().getValue().toString();
        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrices.peek();
        int px = client.player.getBlockX(), pz = client.player.getBlockZ();
        int y0 = Math.max(client.world.getBottomY(), client.player.getBlockY() - BELOW);
        int y1 = Math.min(client.world.getTopY(), client.player.getBlockY() + ABOVE);

        if (filterArea != null && filterArea.dimension().equals(dimension)) {
            drawZone(lines, entry, filterArea, FILTER_COLOR, px, pz, y0, y1);
        }

        for (int i = 0; i < zones.size(); i++) {
            RatesPayloads.ZoneData zone = zones.get(i);

            if (zone.dimension().equals(dimension)) {
                drawZone(lines, entry, zone, colorOf(i), px, pz, y0, y1);
            }
        }

        matrices.pop();
    }

    private static void drawZone(VertexConsumer lines, MatrixStack.Entry entry, RatesPayloads.ZoneData zone, int color,
                                 int px, int pz, int y0, int y1) {
        int x0 = zone.minX(), z0 = zone.minZ(), x1 = zone.maxX() + 1, z1 = zone.maxZ() + 1;
        // the four walls: x = x0 / x1 along z, z = z0 / z1 along x
        wall(lines, entry, true, x0, z0, z1, pz, Math.abs(px - x0), color, y0, y1);
        wall(lines, entry, true, x1, z0, z1, pz, Math.abs(px - x1), color, y0, y1);
        wall(lines, entry, false, z0, x0, x1, px, Math.abs(pz - z0), color, y0, y1);
        wall(lines, entry, false, z1, x0, x1, px, Math.abs(pz - z1), color, y0, y1);
    }

    /**
     * One wall at fixed coordinate {@code at} spanning {@code from..to} along the other axis, drawn only near
     * the player ({@code along} is the player's coordinate on that axis, {@code distance} the distance to the wall).
     */
    private static void wall(VertexConsumer lines, MatrixStack.Entry entry, boolean fixedX, int at, int from, int to,
                             int along, int distance, int color, int y0, int y1) {
        if (distance > RANGE) {
            return;
        }

        int a = Math.max(from, along - RANGE), b = Math.min(to, along + RANGE);

        if (a > b) {
            return;
        }

        float r = (color >> 16 & 255) / 255f, g = (color >> 8 & 255) / 255f, bl = (color & 255) / 255f;

        // vertical lines on the grid, plus the wall ends (zone corners)
        for (int t = Math.floorDiv(a + STEP - 1, STEP) * STEP; t <= b; t += STEP) {
            line(lines, entry, fixedX, at, t, y0, at, t, y1, r, g, bl, 0.6f);
        }

        if (a == from) {
            line(lines, entry, fixedX, at, a, y0, at, a, y1, r, g, bl, 1f);
        }

        if (b == to) {
            line(lines, entry, fixedX, at, b, y0, at, b, y1, r, g, bl, 1f);
        }

        for (int y = Math.floorDiv(y0 + STEP - 1, STEP) * STEP; y <= y1; y += STEP) {
            line(lines, entry, fixedX, at, a, y, at, b, y, r, g, bl, 0.6f);
        }
    }

    private static void line(VertexConsumer lines, MatrixStack.Entry entry, boolean fixedX, int at1, int t1, float y1,
                             int at2, int t2, float y2, float r, float g, float b, float alpha) {
        float ax = fixedX ? at1 : t1, az = fixedX ? t1 : at1, bx = fixedX ? at2 : t2, bz = fixedX ? t2 : at2;
        float nx = bx - ax, ny = y2 - y1, nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

        if (len == 0) {
            return;
        }

        lines.vertex(entry, ax, y1, az).color(r, g, b, alpha).normal(entry, nx / len, ny / len, nz / len);
        lines.vertex(entry, bx, y2, bz).color(r, g, b, alpha).normal(entry, nx / len, ny / len, nz / len);
    }

    /** Top left text: the zones the player is standing in. */
    static void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!enabled || client.player == null || client.world == null || client.options.hudHidden) {
            return;
        }

        String dimension = client.world.getRegistryKey().getValue().toString();
        int x = client.player.getBlockX(), z = client.player.getBlockZ();
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal("[구역 표시 켜짐]"));

        if (filterArea != null && filterArea.dimension().equals(dimension)) {
            lines.add(Text.literal(contains(filterArea, x, z) ? "지방 필터: 경계 안" : "지방 필터: 경계 밖"));
        }

        for (RatesPayloads.ZoneData zone : zones) {
            if (zone.dimension().equals(dimension) && contains(zone, x, z)) {
                lines.add(Text.literal("구역: " + zone.name() + " (" + zone.multipliers().size() + "종)"));
            }
        }

        int y = 4;

        for (Text line : lines) {
            context.drawTextWithShadow(client.textRenderer, line, 4, y, y == 4 ? 0xFFFF55 : 0xFFFFFF);
            y += 10;
        }
    }

    private static boolean contains(RatesPayloads.ZoneData zone, int x, int z) {
        return x >= zone.minX() && x <= zone.maxX() && z >= zone.minZ() && z <= zone.maxZ();
    }
}
