package kr.spawnfilter;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Server side chest menu to pick the spawn regions (no client mod needed).
 * Rows 1-2: regions inside the area, rows 4-5: regions outside the area, row 6: buttons.
 */
public class RegionMenu extends GenericContainerScreenHandler {
    private static final int SIZE = 54;
    private static final int[] INSIDE_SLOTS = {1, 2, 3, 4, 5, 6, 7, 8, 10, 11};
    private static final int[] OUTSIDE_SLOTS = {28, 29, 30, 31, 32, 33, 34, 35, 37, 38};
    private static final int INSIDE_ALL = 16, INSIDE_NONE = 17, OUTSIDE_ALL = 43, OUTSIDE_NONE = 44;
    private static final int CANCEL = 45, TOGGLE_ENABLED = 47, APPLY = 49, INFO = 51, TOGGLE_CLEANUP = 53;

    private static final String[] NAMES = {"관동", "성도", "호연", "신오", "하나", "칼로스", "알로라", "가라르", "히스이", "팔데아"};

    private final SimpleInventory menu;
    private final EnumSet<Region> inside;
    private final EnumSet<Region> outside;
    private boolean enabled;
    private boolean cleanup;

    private RegionMenu(int syncId, PlayerInventory playerInventory, SimpleInventory menu) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, menu, 6);
        this.menu = menu;
        this.inside = copy(SpawnFilterConfig.regions());
        this.outside = copy(SpawnFilterConfig.outsideRegions());
        this.enabled = SpawnFilterConfig.enabled();
        this.cleanup = SpawnFilterConfig.removeExisting();
        refresh();
    }

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inventory, p) -> new RegionMenu(syncId, inventory, new SimpleInventory(SIZE)),
            Text.literal("스폰 필터 - 지방 선택")));
    }

    private static EnumSet<Region> copy(Set<Region> regions) {
        return regions.isEmpty() ? EnumSet.noneOf(Region.class) : EnumSet.copyOf(regions);
    }

    private static String label(Region region) {
        return NAMES[region.ordinal()] + " (" + region.range() + ")";
    }

    private void refresh() {
        for (int i = 0; i < SIZE; i++) {
            menu.setStack(i, ItemStack.EMPTY);
        }

        boolean hasArea = SpawnFilterConfig.area() != null;
        menu.setStack(0, item(Items.GRASS_BLOCK, Text.literal(hasArea ? "관동 맵 안 (경계 안쪽)" : "서버 전체").formatted(Formatting.GREEN),
            "아래에서 나올 지방을 고르세요", "초록 = 나옴, 회색 = 안 나옴"));
        regionRow(INSIDE_SLOTS, inside);
        menu.setStack(INSIDE_ALL, item(Items.LIME_DYE, Text.literal("전체 선택").formatted(Formatting.GREEN)));
        menu.setStack(INSIDE_NONE, item(Items.GRAY_DYE, Text.literal("전체 해제").formatted(Formatting.GRAY)));

        for (int i = 18; i < 27; i++) {
            menu.setStack(i, item(Items.BLACK_STAINED_GLASS_PANE, Text.literal(" ")));
        }

        if (hasArea) {
            menu.setStack(27, item(Items.COMPASS, Text.literal("관동 맵 밖 (경계 바깥)").formatted(Formatting.AQUA),
                "경계: " + SpawnFilterConfig.area(), "이 바깥에서 나올 지방"));
            regionRow(OUTSIDE_SLOTS, outside);
            menu.setStack(OUTSIDE_ALL, item(Items.LIME_DYE, Text.literal("전체 선택").formatted(Formatting.GREEN)));
            menu.setStack(OUTSIDE_NONE, item(Items.GRAY_DYE, Text.literal("전체 해제").formatted(Formatting.GRAY)));
        } else {
            menu.setStack(27, item(Items.COMPASS, Text.literal("경계가 설정되지 않음").formatted(Formatting.GRAY),
                "/spawnfilter area x1 z1 x2 z2 로 경계를 정하면", "경계 안과 밖을 따로 고를 수 있습니다"));
        }

        menu.setStack(CANCEL, item(Items.BARRIER, Text.literal("취소").formatted(Formatting.RED), "바꾼 내용을 버리고 닫기"));
        menu.setStack(TOGGLE_ENABLED, enabled
            ? item(Items.REDSTONE_TORCH, Text.literal("필터: 켜짐").formatted(Formatting.GREEN), "클릭하면 끕니다 (모든 지방이 나옴)")
            : item(Items.LEVER, Text.literal("필터: 꺼짐").formatted(Formatting.GRAY), "클릭하면 켭니다"));
        menu.setStack(APPLY, item(Items.EMERALD_BLOCK, Text.literal("적용").formatted(Formatting.GREEN, Formatting.BOLD),
            "저장하고 바로 적용합니다", "(/reload 실행, 잠깐 렉이 생길 수 있음)"));
        menu.setStack(TOGGLE_CLEANUP, cleanup
            ? item(Items.LAVA_BUCKET, Text.literal("이미 나온 야생 포켓몬: 정리함").formatted(Formatting.GOLD),
                "적용하면 지금 나와 있는 포켓몬 중", "새 설정에 안 맞는 야생 포켓몬을 바로 없앱니다",
                "이후 구역을 다시 불러올 때도 계속 정리", "(잡은/배틀 중/고정된 포켓몬은 제외)", "클릭하면 '그대로 둠'으로 바꿈")
            : item(Items.BUCKET, Text.literal("이미 나온 야생 포켓몬: 그대로 둠").formatted(Formatting.GRAY),
                "이미 나와 있는 포켓몬은 없애지 않고", "앞으로 새로 나오는 포켓몬만 바뀝니다", "클릭하면 '정리함'으로 바꿈"));
        menu.setStack(INFO, item(Items.BOOK, Text.literal("안내").formatted(Formatting.YELLOW),
            "야생 / 낚시 / 미끼 스폰에만 적용", "진화, 알, 레이드, 전설 둥지는 영향 없음"));
    }

    private void regionRow(int[] slots, EnumSet<Region> selected) {
        Region[] regions = Region.values();

        for (int i = 0; i < regions.length && i < slots.length; i++) {
            boolean on = selected.contains(regions[i]);
            ItemStack stack = item(on ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE,
                Text.literal(label(regions[i])).formatted(on ? Formatting.GREEN : Formatting.GRAY),
                on ? "나옴 (클릭하면 끄기)" : "안 나옴 (클릭하면 켜기)");

            if (on) {
                stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }

            menu.setStack(slots[i], stack);
        }
    }

    private static ItemStack item(Item item, Text name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, name.copy().styled(s -> s.withItalic(false)));

        if (lore.length > 0) {
            List<Text> lines = new ArrayList<>();
            Arrays.stream(lore).forEach(l -> lines.add(Text.literal(l).formatted(Formatting.GRAY).styled(s -> s.withItalic(false))));
            stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }

        return stack;
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // nothing in this menu can be taken or moved; every click is a button
        if (slotIndex >= 0 && slotIndex < SIZE && actionType == SlotActionType.PICKUP && player instanceof ServerPlayerEntity serverPlayer) {
            handle(slotIndex, serverPlayer);
        }

        syncState();
    }

    private void handle(int slot, ServerPlayerEntity player) {
        boolean hasArea = SpawnFilterConfig.area() != null;
        int in = indexOf(INSIDE_SLOTS, slot);
        int out = hasArea ? indexOf(OUTSIDE_SLOTS, slot) : -1;

        if (in >= 0) {
            toggle(inside, Region.values()[in]);
        } else if (out >= 0) {
            toggle(outside, Region.values()[out]);
        } else if (slot == INSIDE_ALL) {
            inside.addAll(EnumSet.allOf(Region.class));
        } else if (slot == INSIDE_NONE) {
            inside.clear();
        } else if (hasArea && slot == OUTSIDE_ALL) {
            outside.addAll(EnumSet.allOf(Region.class));
        } else if (hasArea && slot == OUTSIDE_NONE) {
            outside.clear();
        } else if (slot == TOGGLE_ENABLED) {
            enabled = !enabled;
        } else if (slot == TOGGLE_CLEANUP) {
            cleanup = !cleanup;
        } else if (slot == CANCEL) {
            player.closeHandledScreen();
            return;
        } else if (slot == APPLY) {
            apply(player);
            return;
        } else {
            return;
        }

        refresh();
    }

    private static void toggle(EnumSet<Region> set, Region region) {
        if (!set.remove(region)) {
            set.add(region);
        }
    }

    private void apply(ServerPlayerEntity player) {
        if (enabled && inside.isEmpty()) {
            player.sendMessage(Text.literal("[스폰 필터] 경계 안에 나올 지방을 하나 이상 골라 주세요.").formatted(Formatting.RED));
            return;
        }

        SpawnFilterConfig.setSelection(enabled, inside, outside, cleanup);
        player.closeHandledScreen();
        player.sendMessage(Text.literal("[스폰 필터] 저장했습니다. 스폰 목록을 다시 불러옵니다...").formatted(Formatting.GREEN));
        MinecraftServer server = player.getServer();

        if (cleanup) {
            int removed = SpawnFilter.cleanupLoaded(server);
            player.sendMessage(Text.literal("[스폰 필터] 설정에 안 맞는 야생 포켓몬 " + removed + "마리를 정리했습니다.")
                .formatted(Formatting.GOLD));
        }

        server.getCommandManager().executeWithPrefix(server.getCommandSource(), "reload");
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }
}
