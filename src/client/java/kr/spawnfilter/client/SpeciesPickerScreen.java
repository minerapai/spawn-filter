package kr.spawnfilter.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Scrollable, searchable list of all Pokémon species; clicking one hands its id to the callback. */
public class SpeciesPickerScreen extends Screen {
    private static final int WIDTH = 300;
    private static final int ROW = 14;

    private record Entry(String id, int dex, String label, String search) {
    }

    private final Screen parent;
    private final Consumer<String> onPick;
    private final List<Entry> all = new ArrayList<>();
    private List<Entry> shown = new ArrayList<>();
    private String query = "";
    private int scroll;

    private int left;
    private int listTop;
    private int rows;
    private TextFieldWidget searchField;

    public SpeciesPickerScreen(Screen parent, Consumer<String> onPick) {
        super(Text.literal("포켓몬 선택"));
        this.parent = parent;
        this.onPick = onPick;

        for (Species s : PokemonSpecies.getSpecies()) {
            if (!s.getImplemented()) {
                continue;
            }

            String id = s.getResourceIdentifier().getPath();
            String name = s.getTranslatedName().getString();
            all.add(new Entry(id, s.getNationalPokedexNumber(),
                String.format(Locale.ROOT, "#%04d  %s  ", s.getNationalPokedexNumber(), name),
                (name + " " + id + " " + s.getNationalPokedexNumber()).toLowerCase(Locale.ROOT)));
        }

        all.sort(Comparator.comparingInt(Entry::dex).thenComparing(Entry::id));
        filter();
    }

    /** Display name of a species id (falls back to the id itself). */
    static String displayName(String id) {
        Species s = PokemonSpecies.getByName(id);
        return s != null ? s.getTranslatedName().getString() : id;
    }

    private void filter() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        shown = q.isEmpty() ? all : all.stream().filter(e -> e.search().contains(q)).toList();
        scroll = 0;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        int top = 8;
        searchField = new TextFieldWidget(textRenderer, left, top + 14, WIDTH, 18, Text.literal("검색"));
        searchField.setPlaceholder(Text.literal("이름 / 영문 id / 도감번호 검색").formatted(Formatting.DARK_GRAY));
        searchField.setText(query);
        searchField.setChangedListener(v -> {
            query = v;
            filter();
        });
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        listTop = top + 38;
        rows = Math.max(4, (height - listTop - 34) / ROW);
        addDrawableChild(ButtonWidget.builder(Text.literal("취소"), b -> close())
            .dimensions(left + WIDTH - 100, listTop + rows * ROW + 6, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(Math.max(0, shown.size() - rows), scroll - (int) Math.signum(vertical) * 3));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= left && mouseX < left + WIDTH && mouseY >= listTop && mouseY < listTop + rows * ROW) {
            int index = scroll + (int) ((mouseY - listTop) / ROW);

            if (index < shown.size()) {
                onPick.accept(shown.get(index).id());
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        context.fill(left - 2, listTop - 2, left + WIDTH + 2, listTop + rows * ROW + 2, 0x80000000);

        for (int i = 0; i < rows && scroll + i < shown.size(); i++) {
            Entry e = shown.get(scroll + i);
            int y = listTop + i * ROW;
            boolean hover = mouseX >= left && mouseX < left + WIDTH && mouseY >= y && mouseY < y + ROW;

            if (hover) {
                context.fill(left, y, left + WIDTH, y + ROW, 0x40FFFFFF);
            }

            context.drawTextWithShadow(textRenderer, Text.literal(e.label()), left + 4, y + 3, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, Text.literal(e.id()), left + WIDTH - 4 - textRenderer.getWidth(e.id()), y + 3, 0x808080);
        }

        if (shown.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("결과 없음"), left + 4, listTop + 4, 0xA0A0A0);
        } else {
            context.drawTextWithShadow(textRenderer, Text.literal((scroll + 1) + "-" + Math.min(shown.size(), scroll + rows)
                + " / " + shown.size() + "  (휠로 스크롤)"), left, listTop + rows * ROW + 12, 0xA0A0A0);
        }
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
