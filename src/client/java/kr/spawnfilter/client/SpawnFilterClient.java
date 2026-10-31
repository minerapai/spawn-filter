package kr.spawnfilter.client;

import kr.spawnfilter.net.RatesPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class SpawnFilterClient implements ClientModInitializer {
    private static final String CATEGORY = "key.categories.spawnfilter";
    private static KeyBinding regionsKey;
    private static KeyBinding ratesKey;
    private static KeyBinding zonesKey;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RatesPayloads.Snapshot.ID, (payload, context) -> {
            if (context.client().currentScreen instanceof RatesScreen screen) {
                screen.update(payload);
            } else {
                context.client().setScreen(new RatesScreen(payload));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(RatesPayloads.View.ID, (payload, context) -> ZoneOverlay.update(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ZoneOverlay.reset());

        regionsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.spawnfilter.regions", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY));
        ratesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.spawnfilter.rates", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY));
        zonesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.spawnfilter.zones", InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_BACKSLASH, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(SpawnFilterClient::tick);

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ZoneOverlay::render);
        HudRenderCallback.EVENT.register((context, tickCounter) -> ZoneOverlay.renderHud(context));
    }

    private static void tick(MinecraftClient client) {
        while (regionsKey.wasPressed()) {
            command(client, "spawnfilter ui");
        }

        while (ratesKey.wasPressed()) {
            command(client, "spawnfilter rates");
        }

        while (zonesKey.wasPressed()) {
            toggleZones(client);
        }
    }

    private static void command(MinecraftClient client, String command) {
        if (client.player != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    private static void toggleZones(MinecraftClient client) {
        if (client.player == null) {
            return;
        }

        if (!ClientPlayNetworking.canSend(RatesPayloads.ViewRequest.ID)) {
            client.player.sendMessage(Text.literal("[스폰 필터] 이 서버에는 Spawn Filter 모드가 없습니다.").formatted(Formatting.RED), true);
            return;
        }

        ZoneOverlay.enabled = !ZoneOverlay.enabled;
        ClientPlayNetworking.send(new RatesPayloads.ViewRequest(ZoneOverlay.enabled));
        client.player.sendMessage(Text.literal(ZoneOverlay.enabled ? "[스폰 필터] 구역 표시 켜짐" : "[스폰 필터] 구역 표시 꺼짐")
            .formatted(ZoneOverlay.enabled ? Formatting.GREEN : Formatting.GRAY), true);
    }
}
