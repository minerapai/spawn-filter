package kr.spawnfilter.client;

import kr.spawnfilter.net.RatesPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Spawn rates: rarity bucket weights and the list of zones with their species multipliers. */
public class RatesScreen extends Screen {
    static final float MAX_BUCKET = 10000f;
    static final float MAX_MULT = 100f;
    static final int OK = 0xE0E0E0;
    static final int ERROR = 0xFF5555;
    private static final int WIDTH = 360;
    private static final int ROWS = 5;

    private RatesPayloads.Snapshot snapshot;
    private final Map<String, String> buckets = new LinkedHashMap<>();
    /** index 0 is the whole server */
    final List<ZoneState> zones = new ArrayList<>();
    private int scroll;

    private int left;
    private int top;
    private final Map<String, TextFieldWidget> bucketFields = new LinkedHashMap<>();
    private ButtonWidget applyButton;

    public RatesScreen(RatesPayloads.Snapshot snapshot) {
        super(Text.literal("스폰 확률 설정"));
        update(snapshot);
    }

    public void update(RatesPayloads.Snapshot snapshot) {
        this.snapshot = snapshot;
        buckets.clear();
        snapshot.buckets().forEach((name, weight) -> buckets.put(name, format(weight)));
        zones.clear();
        snapshot.zones().forEach(z -> zones.add(ZoneState.of(z)));
        scroll = 0;

        if (client != null && client.currentScreen == this) {
            clearAndInit();
        }
    }

    static String format(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    static Float parse(String text, float max) {
        try {
            float value = Float.parseFloat(text);
            return value >= 0 && value <= max ? value : null;
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static String bucketLabel(String name) {
        return switch (name) {
            case "common" -> "흔함 (common)";
            case "uncommon" -> "드묾 (uncommon)";
            case "rare" -> "희귀 (rare)";
            case "ultra-rare" -> "매우 희귀 (ultra-rare)";
            default -> name;
        };
    }

    @Override
    protected void init() {
        applyButton = null;
        bucketFields.clear();
        left = (width - WIDTH) / 2;
        top = Math.max(6, (height - 260) / 2);

        int y = top + 26;

        for (String name : buckets.keySet()) {
            TextFieldWidget field = new TextFieldWidget(textRenderer, left + 150, y, 60, 16, Text.empty());
            field.setMaxLength(9);
            field.setTextPredicate(s -> s.matches("\\d{0,5}(\\.\\d{0,3})?"));
            field.setText(buckets.get(name));
            field.setChangedListener(v -> {
                buckets.put(name, v);
                refresh();
            });
            bucketFields.put(name, addDrawableChild(field));
            y += 20;
        }

        int listTop = listTop();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, zones.size() - ROWS)));

        for (int i = 0; i < ROWS && scroll + i < zones.size(); i++) {
            int index = scroll + i;
            int ry = listTop + i * 22;
            addDrawableChild(ButtonWidget.builder(Text.literal("편집"), b -> client.setScreen(new ZoneEditScreen(this, index)))
                .dimensions(left + WIDTH - 88, ry, 44, 20).build());
            ButtonWidget delete = addDrawableChild(ButtonWidget.builder(Text.literal("삭제"), b -> {
                zones.remove(index);
                clearAndInit();
            }).dimensions(left + WIDTH - 42, ry, 42, 20).build());
            delete.active = zones.get(index).area; // the whole server entry stays
        }

        int controlsY = listTop + ROWS * 22 + 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("+ 구역 추가 (내 위치 기준)"), b -> {
            String dim = client.world.getRegistryKey().getValue().toString();
            ZoneState zone = ZoneState.newArea(dim, client.player.getBlockX(), client.player.getBlockZ());
            client.setScreen(new ZoneEditScreen(this, zone));
        }).dimensions(left, controlsY, 170, 20).build());
        ButtonWidget up = addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> {
            scroll--;
            clearAndInit();
        }).dimensions(left + WIDTH - 46, controlsY, 22, 20).build());
        ButtonWidget down = addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> {
            scroll++;
            clearAndInit();
        }).dimensions(left + WIDTH - 22, controlsY, 22, 20).build());
        up.active = scroll > 0;
        down.active = scroll + ROWS < zones.size();

        int bottom = controlsY + 28;
        applyButton = addDrawableChild(ButtonWidget.builder(Text.literal("적용"), b -> apply())
            .dimensions(left, bottom, 116, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("희귀도 기본값"), b -> {
            buckets.clear();
            snapshot.defaultBuckets().forEach((name, weight) -> buckets.put(name, format(weight)));
            clearAndInit();
        }).dimensions(left + 122, bottom, 116, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), b -> close())
            .dimensions(left + 244, bottom, 116, 20).build());

        refresh();
    }

    private int listTop() {
        return top + 26 + buckets.size() * 20 + 26;
    }

    /** Called by the zone editor when it is confirmed (index -1 = new zone). */
    void putZone(int index, ZoneState zone) {
        if (index < 0) {
            zones.add(zone);
            scroll = Math.max(0, zones.size() - ROWS);
        } else {
            zones.set(index, zone);
        }
    }

    private void refresh() {
        if (applyButton == null) {
            return;
        }

        boolean valid = true;
        float total = 0;

        for (Map.Entry<String, TextFieldWidget> e : bucketFields.entrySet()) {
            Float value = parse(buckets.get(e.getKey()), MAX_BUCKET);
            e.getValue().setEditableColor(value != null ? OK : ERROR);
            valid &= value != null;
            total += value != null ? value : 0;
        }

        for (ZoneState zone : zones) {
            valid &= zone.valid();
        }

        applyButton.active = valid && total > 0;
    }

    private void apply() {
        Map<String, Float> b = new LinkedHashMap<>();
        buckets.forEach((name, text) -> b.put(name, parse(text, MAX_BUCKET)));
        List<RatesPayloads.ZoneData> z = new ArrayList<>();
        zones.forEach(zone -> z.add(zone.toData()));
        ClientPlayNetworking.send(new RatesPayloads.Apply(b, z));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("희귀도 비율 (가중치, 클수록 자주)").formatted(Formatting.YELLOW),
            left, top + 14, 0xFFFFFF);

        float total = 0;

        for (String text : buckets.values()) {
            Float value = parse(text, MAX_BUCKET);
            total += value != null ? value : 0;
        }

        int y = top + 26;

        for (String name : buckets.keySet()) {
            context.drawTextWithShadow(textRenderer, Text.literal(bucketLabel(name)), left, y + 4, 0xFFFFFF);
            Float value = parse(buckets.get(name), MAX_BUCKET);
            String percent = value != null && total > 0 ? String.format(Locale.ROOT, "= %.2f%%", value * 100 / total) : "";
            context.drawTextWithShadow(textRenderer, Text.literal(percent), left + 216, y + 4, 0x80FF80);
            Float def = snapshot.defaultBuckets().get(name);
            context.drawTextWithShadow(textRenderer, Text.literal(def != null ? "기본 " + format(def) : ""), left + 290, y + 4, 0xA0A0A0);
            y += 20;
        }

        int listTop = listTop();
        context.drawTextWithShadow(textRenderer, Text.literal("구역별 포켓몬 배율 (겹치는 곳은 곱해짐)").formatted(Formatting.YELLOW),
            left, listTop - 12, 0xFFFFFF);

        for (int i = 0; i < ROWS && scroll + i < zones.size(); i++) {
            ZoneState zone = zones.get(scroll + i);
            int ry = listTop + i * 22;
            int color = zone.valid() ? 0xFFFFFF : ERROR;
            long forced = zone.species.stream().filter(r -> r[2].equals("1")).count();
            context.drawTextWithShadow(textRenderer, Text.literal(zone.name + "  (" + zone.species.size() + "종"
                + (forced > 0 ? ", 강제 " + forced : "") + ")"), left, ry + 1, color);
            context.drawTextWithShadow(textRenderer, Text.literal(zone.summary()), left, ry + 11, 0xA0A0A0);
        }

        if (zones.size() > ROWS) {
            context.drawTextWithShadow(textRenderer, Text.literal((scroll + 1) + "-" + Math.min(zones.size(), scroll + ROWS)
                + " / " + zones.size()), left + 176, listTop + ROWS * 22 + 8, 0xA0A0A0);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
