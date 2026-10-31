package kr.spawnfilter.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Consumer;

/** Edits one zone: its name, rectangle and the species multipliers that apply inside it. */
public class ZoneEditScreen extends Screen {
    private static final int WIDTH = 360;
    private static final int ROWS = 6;

    private final RatesScreen parent;
    private final int index;
    private final ZoneState zone;
    private int scroll;

    private int left;
    private int top;
    private ButtonWidget okButton;

    /** Edit an existing zone. */
    ZoneEditScreen(RatesScreen parent, int index) {
        this(parent, index, parent.zones.get(index).copy());
    }

    /** Add a new zone. */
    ZoneEditScreen(RatesScreen parent, ZoneState zone) {
        this(parent, -1, zone);
    }

    private ZoneEditScreen(RatesScreen parent, int index, ZoneState zone) {
        super(Text.literal(zone.area ? "구역 편집" : "전체 서버 배율 편집"));
        this.parent = parent;
        this.index = index;
        this.zone = zone;
    }

    private TextFieldWidget field(int x, int y, int w, String value, Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 16, Text.empty());
        f.setMaxLength(32);
        f.setText(value);
        f.setChangedListener(v -> {
            onChange.accept(v);
            refresh();
        });
        return addDrawableChild(f);
    }

    private TextFieldWidget coord(int x, int y, String value, Consumer<String> onChange) {
        TextFieldWidget f = field(x, y, 60, value, onChange);
        f.setMaxLength(9);
        f.setTextPredicate(s -> s.matches("-?\\d{0,8}"));
        return f;
    }

    @Override
    protected void init() {
        okButton = null;
        left = (width - WIDTH) / 2;
        top = Math.max(6, (height - 270) / 2);
        int y = top + 16;

        if (zone.area) {
            field(left + 40, y, 160, zone.name, v -> zone.name = v);
            y += 22;
            coord(left + 40, y, zone.x1, v -> zone.x1 = v);
            coord(left + 120, y, zone.z1, v -> zone.z1 = v);
            addDrawableChild(ButtonWidget.builder(Text.literal("내 위치"), b -> here(true))
                .dimensions(left + 186, y - 2, 60, 20).build());
            y += 20;
            coord(left + 40, y, zone.x2, v -> zone.x2 = v);
            coord(left + 120, y, zone.z2, v -> zone.z2 = v);
            addDrawableChild(ButtonWidget.builder(Text.literal("내 위치"), b -> here(false))
                .dimensions(left + 186, y - 2, 60, 20).build());
            y += 24;
        }

        int listTop = y + 12;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, zone.species.size() - ROWS)));

        for (int i = 0; i < ROWS && scroll + i < zone.species.size(); i++) {
            String[] row = zone.species.get(scroll + i);
            int ry = listTop + i * 22;
            addDrawableChild(ButtonWidget.builder(Text.literal(SpeciesPickerScreen.displayName(row[0])),
                b -> client.setScreen(new SpeciesPickerScreen(this, id -> {
                    row[0] = id;
                    client.setScreen(this);
                }))).dimensions(left, ry, 140, 20).build());
            boolean forced = row[2].equals("1");
            addDrawableChild(ButtonWidget.builder(Text.literal(forced ? "강제 ON" : "강제 OFF")
                    .formatted(forced ? Formatting.GREEN : Formatting.GRAY), b -> {
                    row[2] = row[2].equals("1") ? "0" : "1";
                    clearAndInit();
                }).dimensions(left + 144, ry, 54, 20)
                .tooltip(Tooltip.of(Text.literal("ON: 이 구역 안에서는 바이옴/시간/날씨 조건과 지방 필터를 무시하고 나옵니다. "
                    + "땅/물 같은 스폰 방식은 그대로입니다.")))
                .build());
            TextFieldWidget mult = field(left + 212, ry + 2, 50, row[1], v -> row[1] = v);
            mult.setMaxLength(7);
            mult.setTextPredicate(s -> s.matches("\\d{0,3}(\\.\\d{0,3})?"));
            addDrawableChild(ButtonWidget.builder(Text.literal("삭제"), b -> {
                zone.species.remove(row);
                clearAndInit();
            }).dimensions(left + WIDTH - 44, ry, 44, 20).build());
        }

        int controlsY = listTop + ROWS * 22 + 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("+ 포켓몬 추가"), b -> client.setScreen(new SpeciesPickerScreen(this, id -> {
            zone.species.removeIf(r -> r[0].equals(id));
            zone.species.add(new String[]{id, "2", "0"});
            scroll = Math.max(0, zone.species.size() - ROWS);
            client.setScreen(this);
        }))).dimensions(left, controlsY, 120, 20).build());
        ButtonWidget up = addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> {
            scroll--;
            clearAndInit();
        }).dimensions(left + WIDTH - 46, controlsY, 22, 20).build());
        ButtonWidget down = addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> {
            scroll++;
            clearAndInit();
        }).dimensions(left + WIDTH - 22, controlsY, 22, 20).build());
        up.active = scroll > 0;
        down.active = scroll + ROWS < zone.species.size();

        int bottom = controlsY + 28;
        okButton = addDrawableChild(ButtonWidget.builder(Text.literal("확인"), b -> {
            parent.putZone(index, zone);
            client.setScreen(parent);
        }).dimensions(left, bottom, 177, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("취소"), b -> close())
            .dimensions(left + 183, bottom, 177, 20).build());

        refresh();
    }

    private void here(boolean first) {
        String x = Integer.toString(client.player.getBlockX());
        String z = Integer.toString(client.player.getBlockZ());
        zone.dimension = client.world.getRegistryKey().getValue().toString();

        if (first) {
            zone.x1 = x;
            zone.z1 = z;
        } else {
            zone.x2 = x;
            zone.z2 = z;
        }

        clearAndInit();
    }

    private void refresh() {
        if (okButton != null) {
            okButton.active = zone.valid() && !zone.name.isBlank();
        }
    }

    private int listTop() {
        return top + 16 + (zone.area ? 66 : 0) + 12;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top, 0xFFFFFF);
        int y = top + 16;

        if (zone.area) {
            context.drawTextWithShadow(textRenderer, Text.literal("이름"), left, y + 4, 0xFFFFFF);
            y += 22;
            context.drawTextWithShadow(textRenderer, Text.literal("모서리1"), left, y + 4, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, Text.literal("x"), left + 32, y + 4, 0xA0A0A0);
            context.drawTextWithShadow(textRenderer, Text.literal("z"), left + 112, y + 4, 0xA0A0A0);
            y += 20;
            context.drawTextWithShadow(textRenderer, Text.literal("모서리2"), left, y + 4, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, Text.literal("x"), left + 32, y + 4, 0xA0A0A0);
            context.drawTextWithShadow(textRenderer, Text.literal("z"), left + 112, y + 4, 0xA0A0A0);
            context.drawTextWithShadow(textRenderer, Text.literal(zone.dimension), left + 252, y - 14, 0x808080);
        }

        int listTop = listTop();
        context.drawTextWithShadow(textRenderer, Text.literal("포켓몬 (클릭해서 변경)").formatted(Formatting.YELLOW), left, listTop - 12, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("원래 안 나와도").formatted(Formatting.YELLOW), left + 144, listTop - 12, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, Text.literal("배율").formatted(Formatting.YELLOW), left + 212, listTop - 12, 0xFFFFFF);

        for (int i = 0; i < ROWS && scroll + i < zone.species.size(); i++) {
            String[] row = zone.species.get(scroll + i);
            int ry = listTop + i * 22;
            Float m = RatesScreen.parse(row[1], RatesScreen.MAX_MULT);
            context.drawTextWithShadow(textRenderer, Text.literal("×"), left + 203, ry + 6, m != null ? 0xFFFFFF : RatesScreen.ERROR);
            context.drawTextWithShadow(textRenderer, Text.literal(m == null ? "0~100" : m == 0 ? "안나옴" : ""), left + 266, ry + 6,
                m == null ? RatesScreen.ERROR : 0xA0A0A0);
        }

        if (zone.species.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("아직 없음 - [+ 포켓몬 추가]로 목록에서 고르세요"), left, listTop + 6, 0xA0A0A0);
        }

        context.drawTextWithShadow(textRenderer, Text.literal("배율 2 = 2배 자주, 0.5 = 절반, 0 = 이 구역에서 안 나옴"), left,
            listTop + ROWS * 22 + 30 + 22 + 4, 0x808080);
        context.drawTextWithShadow(textRenderer, Text.literal("강제 ON = 바이옴/시간이 안 맞거나 지방 필터에 막혀도 이 구역에선 나옴"), left,
            listTop + ROWS * 22 + 30 + 22 + 14, 0x808080);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
