package com.karashi.limitedspectator.client;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

// Handles client-side events for limited spectator mode.
@EventBusSubscriber(modid = "limitedspectator", value = Dist.CLIENT)
public class ClientEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEventHandler.class);
    private static final Minecraft MC = Minecraft.getInstance();

    // Local toggle to temporarily display the HUD with F1
    private static boolean hudForceVisible = false;

    // Previous state of the HideGui button (edge ​​detection)
    private static boolean prevHideGuiDown = false;

    // Unique variable for HUD control
    private static boolean hudHidden = false;

    // Called by the packet received from the server
    public static void setHudHidden(boolean hidden) {
        hudHidden = hidden;

        // When the server forces hide/reset, we undo the local toggle
        hudForceVisible = false;
        prevHideGuiDown = false;

        if (MC != null && MC.options != null) {
            // Always hide HUD in spectator mode, F1 can toggle temporarily
            MC.options.hideGui = hidden && !hudForceVisible;
        }
    }

    // Block right click on blocks
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (MC.player == null) return;

        // Detect "limited" spectator state
        boolean isFakeSpectator =
                MC.player.getAbilities().mayfly &&
                Minecraft.getInstance().gameMode.getPlayerMode() == GameType.ADVENTURE &&
                !MC.player.isCreative();

        if (!isFakeSpectator) return;

        // --- selective block ---
        var level = event.getLevel();
        var pos = event.getPos();
        var state = level.getBlockState(pos);

        if (state == null) return;

        // Block type
        var block = state.getBlock();

        // Get block ID for comparison with config list
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();

        // Check if block is in the interactable list from config
        if (com.karashi.limitedspectator.ModConfig.interactableBlocks.contains(blockId)) {
            return; // Allow interaction
        }

        // Everything else is blocked
        event.setCanceled(true);
    }

    // Block right click in air (use items)
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (MC.player == null) return;
        if (MC.player.isSpectator()) event.setCanceled(true);
    }

    // Left click block (attack or destroy blocks)
    @SubscribeEvent
    public static void onMouseClick(InputEvent.InteractionKeyMappingTriggered event) {
        if (MC.player == null) return;
        if (MC.player.isSpectator()) {
            event.setCanceled(true);
            // FOR DEBUGGING
            // LOGGER.debug("Mouse input cancelled for spectator.");
        }
    }

    // Blocks vanilla HUD rendering, but leaves chat and overlay
    @SubscribeEvent
    public static void onRenderHud(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();

        // If the client is set to hide the HUD (calculated in onClientTick), cancel vanilla rendering
        if (mc.options != null && mc.options.hideGui) {
            // We do not delete active GUIs (screen != null) to avoid interfering with inventory/menus
            if (mc.screen == null) {
                event.setCanceled(true);
            }
        }

        // FOR DEBUGGING
        // LOGGER.debug("Conditional HUD rendering. Status: {}", hudHidden ? "hidden" : "active");
    }

    // Automatically update HUD when entering/exiting the "fake spectator"
    @SuppressWarnings("deprecation")
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        MultiPlayerGameMode gm = mc.gameMode;
        boolean isFakeSpectator =
                gm != null &&
                gm.getPlayerMode() == GameType.ADVENTURE &&
                mc.player.getAbilities().mayfly &&
                !mc.player.isCreative();

        // --- HUD Management Logic ---
        // Always manage HUD in spectator mode
        // --- F1 Toggle Management (Hide GUI) ---
        if (isFakeSpectator) {
            boolean currentlyDown = InputConstants.isKeyDown(
                    // MC 26.1: InputConstants.isKeyDown now takes the Window object
                    // directly (no longer the raw GLFW handle).
                    Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_F1
            );

            if (currentlyDown && !prevHideGuiDown) {
                hudForceVisible = !hudForceVisible;
            }
            prevHideGuiDown = currentlyDown;
        }
        // --- End F1 Toggle ---

        // Local auto-sync (in case of packet loss or client reload)
        if (isFakeSpectator && !hudHidden) {
            hudHidden = true;
        } else if (!isFakeSpectator && hudHidden) {
            // when you exit fake spectator mode, reset everything
            hudForceVisible = false;
            mc.options.hideGui = false;
            hudHidden = false;
        }

        // --- Apply Actual Status ---
        // Only in limited spectator mode do we force HUD visibility
        if (isFakeSpectator) {
            boolean effectiveHidden = hudHidden && !hudForceVisible;
            mc.options.hideGui = effectiveHidden;
        }

        // FOR DEBUGGING
        /* if (hudHidden) {
            LOGGER.debug("Hidden HUD in limited spectator mode.");
        } else {
            LOGGER.debug("HUD visible or normal mode.");
        } */
    }
}
