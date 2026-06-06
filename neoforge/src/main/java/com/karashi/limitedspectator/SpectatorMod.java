package com.karashi.limitedspectator;

// Import Java standard
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import com.karashi.limitedspectator.network.NetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceKey;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.ModConfigSpec;

@Mod("limitedspectator")
public class SpectatorMod {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpectatorMod.class);
    public static final String MODID = "limitedspectator";

    // ---- MC 26.1 migration helpers (extracted to keep the bulk of the class unchanged) ----

    /**
     * Vanilla 1.21.x had {@code displayMessage(player,Component, boolean)} where the
     * boolean toggled between chat and action-bar. MC 26.1 split that into two distinct
     * methods (no more boolean overload). Centralise the branching here so call sites stay
     * tidy.
     */
    private static void displayMessage(ServerPlayer player, Component message, boolean useActionBar) {
        if (useActionBar) {
            player.sendOverlayMessage(message);
        } else {
            player.sendSystemMessage(message);
        }
    }

    /**
     * Vanilla 1.21.x had {@code CommandSourceStack.hasPermission(int)} (op-level check).
     * MC 26.1 replaced the int-level system with the {@link PermissionSet} interface and
     * named {@code Permission} objects. The full migration to per-permission checks is a
     * larger refactor — for the 3.0.0 hot path we keep the same semantics by mapping:
     * <ul>
     *   <li>level &le; 0 (everyone): always allowed</li>
     *   <li>level &ge; 1 (op territory): only the server console / a player with
     *       {@code ALL_PERMISSIONS}</li>
     * </ul>
     * TODO(mc-26.1): replace with proper named-permission checks once the upstream
     * NeoForge {@code PermissionAPI} story for 26.1 stabilises.
     */
    private static boolean hasPermissionLevel(CommandSourceStack source, int level) {
        if (level <= 0) return true;
        return source.permissions() == PermissionSet.ALL_PERMISSIONS;
    }

    // ---- end migration helpers ----

    // Core spectator manager - handles all business logic
    private static SpectatorManager spectatorManager;
    
    // Legacy compatibility - deprecated but maintained for backward compatibility
    @Deprecated(forRemoval = false)
    private static final HashMap<UUID, Vec3> spectatorStartPositions = new HashMap<>();
    @Deprecated(forRemoval = false)
    private static final HashMap<UUID, ResourceKey<Level>> spectatorStartDimensions = new HashMap<>();
    @Deprecated(forRemoval = false)
    private static final HashMap<UUID, Boolean> inSpectatorMode = new HashMap<>();

    /**
     * Public API method for Mixins to check if a player is in limited spectator mode.
     * @param playerUUID The player's UUID
     * @return true if the player is in limited spectator mode
     */
    public static boolean isInLimitedSpectatorMode(UUID playerUUID) {
        if (spectatorManager != null) {
            return spectatorManager.isInSpectatorMode(playerUUID);
        }
        // Fallback to legacy for backward compatibility
        return inSpectatorMode.getOrDefault(playerUUID, false);
    }

    public SpectatorMod(IEventBus modBus, ModContainer modContainer) {
        LOGGER.info("Mod initialized successfully.");
        NeoForge.EVENT_BUS.register(this);

        // Register configuration
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, com.karashi.limitedspectator.ModConfig.SPEC);
        LOGGER.info("Configuration registered: limitedspectator-common.toml");

        // Register config event listener
        modBus.addListener(com.karashi.limitedspectator.ModConfig::onLoad);
        modBus.addListener(this::onConfigLoad);

        // Register MOD bus events (non-deprecated way for NeoForge 1.21.1+)
        modBus.addListener(this::onRegisterPayloads);
    }
    
    /**
     * Initialize SpectatorManager when config is loaded.
     * This ensures the manager has access to the latest config values.
     */
    private void onConfigLoad(net.neoforged.fml.event.config.ModConfigEvent event) {
        if (spectatorManager == null) {
            spectatorManager = new SpectatorManager(NeoForgeSpectatorConfig.getInstance());
            LOGGER.info("SpectatorManager initialized with NeoForge configuration");
        }
    }
    
    /**
     * Gets the SpectatorManager instance, initializing it if necessary.
     * Thread-safe lazy initialization.
     */
    private static synchronized SpectatorManager getSpectatorManager() {
        if (spectatorManager == null) {
            spectatorManager = new SpectatorManager(NeoForgeSpectatorConfig.getInstance());
            LOGGER.warn("SpectatorManager initialized lazily - this should not happen in normal operation");
        }
        return spectatorManager;
    }

    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        NetworkHandler.register(event);
    }

    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onCommandRegister(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        // Command: /spectator
        dispatcher.register(Commands.literal("spectator")
            .requires(source -> {
                if (com.karashi.limitedspectator.ModConfig.requireOpForSpectator) {
                    return hasPermissionLevel(source,2); // OP level
                }
                return hasPermissionLevel(source,com.karashi.limitedspectator.ModConfig.spectatorCommandPermissionLevel);
            })
            .executes(ctx -> {
                try {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                    // Debug or control — needed to understand who and where
                    LOGGER.info("Running /spectator command for {} at coordinates [{}, {}, {}]",
                        player.getName().getString(),
                        String.format("%.1f", player.getX()),
                        String.format("%.1f", player.getY()),
                        String.format("%.1f", player.getZ()));

                    // Set gamemode based on config
                    GameType targetMode = com.karashi.limitedspectator.ModConfig.spectatorGamemode.equalsIgnoreCase("SPECTATOR")
                        ? GameType.SPECTATOR
                        : GameType.ADVENTURE;
                    player.setGameMode(targetMode);

                    // Set abilities based on config
                    if (com.karashi.limitedspectator.ModConfig.enableFlight) {
                        player.getAbilities().mayfly = true;
                    }
                    player.getAbilities().invulnerable = com.karashi.limitedspectator.ModConfig.enableInvulnerability;

                    player.onUpdateAbilities();

                    // Save start position and dimension using SpectatorManager
                    SpectatorManager manager = getSpectatorManager();
                    manager.enterSpectatorMode(player.getUUID(), player.position(), player.level().dimension());
                    
                    // Dual-write to legacy maps for backward compatibility
                    spectatorStartPositions.put(player.getUUID(), player.position());
                    spectatorStartDimensions.put(player.getUUID(), player.level().dimension());
                    inSpectatorMode.put(player.getUUID(), true);

                    // Hide HUD (always enabled, F1 can toggle temporarily)
                    NetworkHandler.sendHudState(player, true);

                    // Build and send message
                    MutableComponent message = Component.translatable("limitedspectator.command.spectator.activated")
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(" ").withStyle(ChatFormatting.GRAY));

                    if (com.karashi.limitedspectator.ModConfig.maxDistance > 0) {
                        message = message.append(Component.translatable("limitedspectator.command.spectator.radius",
                            com.karashi.limitedspectator.ModConfig.maxDistance).withStyle(ChatFormatting.GRAY));
                    } else {
                        message = message.append(Component.translatable("limitedspectator.command.spectator.no_limit")
                            .withStyle(ChatFormatting.GRAY));
                    }

                    displayMessage(player,message, com.karashi.limitedspectator.ModConfig.useActionBarMessages);

                    return 1;
                } catch (Exception e) {
                    LOGGER.error("Error executing /spectator command", e);
                    ctx.getSource().sendFailure(Component.literal("§cInternal error: " + e));
                    return 0;
                }
            })
        );

        // Command: /survival
        dispatcher.register(Commands.literal("survival")
            .requires(source -> {
                if (com.karashi.limitedspectator.ModConfig.requireOpForSpectator) {
                    return hasPermissionLevel(source,2); // OP level
                }
                return hasPermissionLevel(source,com.karashi.limitedspectator.ModConfig.survivalCommandPermissionLevel);
            })
            .executes(ctx -> {
                try {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                    // Exit spectator mode using SpectatorManager
                    SpectatorManager manager = getSpectatorManager();
                    SpectatorState state = manager.exitSpectatorMode(player.getUUID());

                    // Teleport player back to start position if available
                    if (state != null) {
                        Vec3 startPos = state.startPosition();
                        ResourceKey<Level> startDimension = state.startDimension();
                        
                        // Check if player is in a different dimension
                        if (!player.level().dimension().equals(startDimension)) {
                            // Get the target dimension
                            ServerLevel targetLevel = player.level().getServer().getLevel(startDimension);
                            if (targetLevel != null) {
                                // Teleport to the correct dimension and position
                                // MC 26.1 widened teleportTo() — needs an explicit set of
                                // relative axes and a "preserveLookDirection" flag. We pass
                                // an empty Set (all coords absolute) and false (keep new yaw/pitch).
                                player.teleportTo(targetLevel, startPos.x, startPos.y, startPos.z,
                                        Set.of(), player.getYRot(), player.getXRot(), false);
                            } else {
                                // Fallback: just teleport to position in current dimension
                                player.teleportTo(startPos.x, startPos.y, startPos.z);
                            }
                        } else {
                            // Same dimension, just teleport to position
                            player.teleportTo(startPos.x, startPos.y, startPos.z);
                        }
                    }

                    // Also remove from legacy maps for backward compatibility
                    spectatorStartPositions.remove(player.getUUID());
                    spectatorStartDimensions.remove(player.getUUID());
                    inSpectatorMode.remove(player.getUUID());

                    // Force survival mode
                    player.setGameMode(GameType.SURVIVAL);

                    // Disable flight and spectator abilities
                    player.getAbilities().flying = false;
                    player.getAbilities().mayfly = false;
                    player.getAbilities().invulnerable = false;
                    player.onUpdateAbilities();

                    // Show HUD again
                    NetworkHandler.sendHudState(player, false); // false = show HUD

                    MutableComponent message = Component.translatable("limitedspectator.command.survival.activated")
                        .withStyle(ChatFormatting.GREEN);

                    displayMessage(player,message, com.karashi.limitedspectator.ModConfig.useActionBarMessages);
                    return 1;

                } catch (Exception e) {
                    LOGGER.error("Error executing /survival command", e);
                    ctx.getSource().sendFailure(Component.literal("§cInternal error: " + e.getMessage()));
                    return 0;
                }
            })
        );
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        SpectatorManager manager = getSpectatorManager();
        SpectatorConfig config = manager.getConfig();
        
        // Skip if distance limit is disabled
        if (!config.isDistanceLimitEnabled()) return;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                // Use SpectatorManager for validation
                DistanceValidationResult result = manager.validateDistance(player.getUUID(), player.position());
                
                if (result.isExceeded()) {
                    Vec3 targetPos = result.targetPosition();
                    
                    if (config.shouldTeleportBackOnExceed()) {
                        // Teleport the player back to start position
                        player.teleportTo(targetPos.x, targetPos.y, targetPos.z);
                        displayMessage(player,
                            Component.translatable("limitedspectator.error.distance_exceeded").withStyle(ChatFormatting.RED),
                            config.shouldUseActionBarMessages()
                        );
                    } else {
                        // Stop the player at the boundary
                        Vec3 currentPos = player.position();
                        Vec3 direction = currentPos.subtract(targetPos).normalize();
                        Vec3 boundaryPos = targetPos.add(direction.scale(config.getMaxDistance()));
                        player.teleportTo(boundaryPos.x, boundaryPos.y, boundaryPos.z);

                        if (config.shouldShowDistanceWarnings()) {
                            displayMessage(player,
                                Component.translatable("limitedspectator.error.distance_reached").withStyle(ChatFormatting.RED),
                                config.shouldUseActionBarMessages()
                            );
                        }
                    }
                }
            }
        }
    }

    // When changing game mode → update skills
    @SubscribeEvent
    public void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        player.getAbilities().flying = player.isSpectator();
        player.onUpdateAbilities();

        // FOR DEBUGGING
        // LOGGER.debug("Game mode change detected for {}: {}", player.getName().getString(),
        //    player.gameMode.getGameModeForPlayer());
    }

    // Block breaking and placing are always disabled in ADVENTURE mode
    // No event handlers needed - ADVENTURE mode blocks these actions natively

    // Handle right-click for block interactions
    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Check if player is in limited spectator mode
        if (!inSpectatorMode.getOrDefault(player.getUUID(), false)) return;

        // Get clicked block
        BlockState state = event.getLevel().getBlockState(event.getPos());
        Block block = state.getBlock();
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();

        // Check if block is in the interactable whitelist
        if (com.karashi.limitedspectator.ModConfig.interactableBlocks.contains(blockId)) {
            return; // Allow interaction with whitelisted blocks
        }

        // Block all other interactions
        event.setCanceled(true);

        // FOR DEBUGGING
        // LOGGER.debug("Interaction blocked by {} with block {}", player.getName().getString(), blockId);
    }

    // Returns to survival when you log out
    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onPlayerLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        SpectatorManager manager = getSpectatorManager();
        
        // Check if player was in spectator mode
        if (manager.isInSpectatorMode(player.getUUID())) {
            SpectatorState state = manager.exitSpectatorMode(player.getUUID());
            
            // Restore saved position if configured
            if (state != null && manager.getConfig().shouldResetPositionOnLogout()) {
                Vec3 startPos = state.startPosition();
                player.teleportTo(startPos.x, startPos.y, startPos.z);
            }

            // Also clean up legacy maps
            spectatorStartPositions.remove(player.getUUID());
            spectatorStartDimensions.remove(player.getUUID());
            inSpectatorMode.remove(player.getUUID());

            // Force survival mode
            player.setGameMode(GameType.SURVIVAL);

            // Deactivate flight and invulnerability
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.getAbilities().invulnerable = false;
            player.onUpdateAbilities();

            LOGGER.info("{} left the server. Spectator mode automatically disabled.", player.getName().getString());
        }
    }

    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onPlayerChangeDimension(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Skip if dimension travel is allowed in config
        if (com.karashi.limitedspectator.ModConfig.allowDimensionTravel) return;

        // Check if the player is in limited spectator mode
        if (inSpectatorMode.getOrDefault(player.getUUID(), false)) {
            event.setCanceled(true);
            displayMessage(player,
                Component.translatable("limitedspectator.error.dimension_blocked").withStyle(ChatFormatting.RED),
                com.karashi.limitedspectator.ModConfig.useActionBarMessages
            );
        }

        // FOR DEBUGGING
        // LOGGER.debug("Player {} attempted to change dimension while in limited spectator mode", player.getName().getString());
    }


    // Disable attacks in limited spectator mode (except PvP if configured)
    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onPlayerAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        // Check if player is in limited spectator mode using tracking map
        if (!inSpectatorMode.getOrDefault(serverPlayer.getUUID(), false)) return;

        // Check if target is a player (PvP)
        boolean isTargetPlayer = event.getTarget() instanceof Player;

        if (isTargetPlayer) {
            // PvP attack - check config
            if (!com.karashi.limitedspectator.ModConfig.allowPvp) {
                event.setCanceled(true);
            }
        } else {
            // Mob/entity attack - always blocked (mobs don't counterattack due to mayfly=true)
            event.setCanceled(true);
        }

        // FOR DEBUG AND CONTROL
        // LOGGER.debug("Attack attempted by {} in limited spectator mode on {}", player.getName().getString(),
        //    event.getTarget().getName().getString());
    }

    // Block item dropping in limited spectator mode
    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        // Check if player is in limited spectator mode using tracking map
        if (!inSpectatorMode.getOrDefault(serverPlayer.getUUID(), false)) return;

        // Check config
        if (!com.karashi.limitedspectator.ModConfig.allowItemDrop) {
            // Cancel the event to prevent item from entering the world
            event.setCanceled(true);

            // Return the item to the player's inventory
            // (The item was already removed from inventory before this event fires)
            serverPlayer.addItem(event.getEntity().getItem().copy());

            // Optional: feedback message for the player
            // serverPlayer.displayClientMessage(Component.literal("§cYou cannot drop items in spectator mode!"), true);
        }

        // FOR DEBUG AND CONTROL
        // LOGGER.debug("Item drop attempted by {} in limited spectator mode", player.getName().getString());
    }

    // Block item pickup in limited spectator mode
    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        // Check if player is in limited spectator mode using tracking map
        if (!inSpectatorMode.getOrDefault(serverPlayer.getUUID(), false)) return;

        // Check config
        if (!com.karashi.limitedspectator.ModConfig.allowItemPickup) {
            // Completely block item pickup
            event.setCanPickup(TriState.FALSE);

            // Optional: feedback message for the player
            // serverPlayer.displayClientMessage(Component.literal("§cYou cannot pick up items in spectator mode!"), true);
        }

        // FOR DEBUG AND CONTROL
        // LOGGER.debug("Item pickup attempted by {} in limited spectator mode", player.getName().getString());
    }

    // Block inventory crafting in limited spectator mode
    @SuppressWarnings({"unused", "deprecation"})
    @SubscribeEvent
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Check if player is in limited spectator mode using tracking map
        if (!inSpectatorMode.getOrDefault(player.getUUID(), false)) return;

        // Check config
        if (!com.karashi.limitedspectator.ModConfig.allowInventoryCrafting) {
            // ItemCraftedEvent is not cancellable, so we need to:
            // 1. Save the ingredients from the crafting slots (before they're cleared)
            // 2. Remove the crafted item
            // 3. Restore the ingredients to the player

            net.minecraft.world.item.ItemStack craftedItem = event.getCrafting().copy();
            net.minecraft.world.Container craftingInventory = event.getInventory();

            // Save all ingredients from the crafting container
            // The event fires before ingredients are consumed, so we save them as-is
            java.util.List<net.minecraft.world.item.ItemStack> ingredients = new java.util.ArrayList<>();

            for (int i = 0; i < craftingInventory.getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = craftingInventory.getItem(i);
                if (!stack.isEmpty()) {
                    // Copy the ingredient exactly as it is (no need to grow)
                    net.minecraft.world.item.ItemStack ingredient = stack.copy();
                    ingredients.add(ingredient);
                }
            }

            // Remove the crafted item from player's cursor immediately
            player.containerMenu.setCarried(net.minecraft.world.item.ItemStack.EMPTY);

            // CRITICAL: Clear the crafting container to prevent duplication
            // This removes any remaining items from the crafting grid
            for (int i = 0; i < craftingInventory.getContainerSize(); i++) {
                craftingInventory.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            }

            // Restore ingredients to player's inventory
            for (net.minecraft.world.item.ItemStack ingredient : ingredients) {
                if (!ingredient.isEmpty()) {
                    // addItem returns true if the item was fully added, false otherwise
                    boolean added = player.addItem(ingredient);
                    if (!added) {
                        // If inventory is full, drop the item on the ground
                        player.drop(ingredient, false);
                    }
                }
            }

            // Sync inventory to client
            player.containerMenu.broadcastChanges();

            // Feedback message for the player
            displayMessage(player,
                Component.translatable("limitedspectator.error.crafting_blocked").withStyle(ChatFormatting.RED),
                com.karashi.limitedspectator.ModConfig.useActionBarMessages
            );
        }

        // FOR DEBUG AND CONTROL
        // LOGGER.debug("Crafting attempted by {} in limited spectator mode", player.getName().getString());
    }
}
