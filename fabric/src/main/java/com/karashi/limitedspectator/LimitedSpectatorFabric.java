package com.karashi.limitedspectator;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric entry point for Limited Spectator mod.
 * 
 * Provides full feature parity with NeoForge version:
 * - Configurable permissions
 * - Distance validation
 * - Hot-reload support
 * - Cross-version compatibility (MC 1.21.x)
 */
public class LimitedSpectatorFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("LimitedSpectator");
    public static final String MOD_ID = "limitedspectator";
    private static SpectatorManager spectatorManager;
    private static ConfigReloadWatcher configWatcher;

    /**
     * MC 26.1 migration: ServerPlayer.displayClientMessage(Component, boolean) was
     * split into sendOverlayMessage() (action-bar) and sendSystemMessage() (chat).
     * Centralised here so call sites stay tidy.
     */
    private static void displayMessage(ServerPlayer player, Component message, boolean useActionBar) {
        if (useActionBar) {
            player.sendOverlayMessage(message);
        } else {
            player.sendSystemMessage(message);
        }
    }
    
    @Override
    public void onInitialize() {
        // Load configuration from file (or use defaults)
        java.nio.file.Path configDir = java.nio.file.Paths.get("config");
        java.nio.file.Path configFile = configDir.resolve("limitedspectator-common.toml");
        
        try {
            java.nio.file.Files.createDirectories(configDir);
            CommonConfig.getInstance().loadFromFile(configDir);
        } catch (Exception e) {
            LOGGER.warn("Failed to load config file, using defaults", e);
        }
        
        spectatorManager = new SpectatorManager(FabricSpectatorConfig.getInstance());
        
        // Initialize config watcher for hot-reload
        configWatcher = new ConfigReloadWatcher(configFile, CommonConfig.getInstance());
        
        registerCommands();
        registerEvents();
        registerLifecycleEvents();
        
        LOGGER.info("Fabric mod initialized with hot-reload support");
    }
    
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CommonConfig config = CommonConfig.getInstance();
            
            // /spectator command - with configurable permissions
            dispatcher.register(Commands.literal("spectator")
                .requires(source -> PermissionHelper.checkPermission(
                    source,
                    config.requireOpForSpectator,
                    config.spectatorCommandPermissionLevel
                ))
                .executes(ctx -> {
                    try {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        
                        LOGGER.info("Running /spectator command for {} at coordinates [{}, {}, {}]",
                            player.getName().getString(),
                            String.format("%.1f", player.getX()),
                            String.format("%.1f", player.getY()),
                            String.format("%.1f", player.getZ()));
                        
                        // Set gamemode based on config
                        GameType targetMode = config.spectatorGamemode.equalsIgnoreCase("SPECTATOR")
                            ? GameType.SPECTATOR
                            : GameType.ADVENTURE;
                        player.setGameMode(targetMode);
                        
                        // Set abilities based on config
                        if (config.enableFlight) {
                            player.getAbilities().mayfly = true;
                        }
                        player.getAbilities().invulnerable = config.enableInvulnerability;
                        player.onUpdateAbilities();
                        
                        // Save state using SpectatorManager
                        spectatorManager.enterSpectatorMode(
                            player.getUUID(),
                            player.position(),
                            player.level().dimension()
                        );
                        
                        // Build and send message
                        MutableComponent message = Component.translatable("limitedspectator.command.spectator.activated")
                            .withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
                        
                        if (config.maxDistance > 0) {
                            message = message.append(Component.translatable("limitedspectator.command.spectator.radius",
                                (int)config.maxDistance).withStyle(ChatFormatting.GRAY));
                        } else {
                            message = message.append(Component.translatable("limitedspectator.command.spectator.no_limit")
                                .withStyle(ChatFormatting.GRAY));
                        }
                        
                        displayMessage(player,message, config.useActionBarMessages);
                        
                        return 1;
                    } catch (Exception e) {
                        LOGGER.error("Error executing /spectator command", e);
                        ctx.getSource().sendFailure(Component.literal("§cInternal error: " + e.getMessage()));
                        return 0;
                    }
                })
            );
            
            // /survival command - with configurable permissions
            dispatcher.register(Commands.literal("survival")
                .requires(source -> PermissionHelper.checkPermission(
                    source,
                    config.requireOpForSpectator,
                    config.survivalCommandPermissionLevel
                ))
                .executes(ctx -> {
                    try {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        
                        // Exit spectator mode using SpectatorManager
                        SpectatorState state = spectatorManager.exitSpectatorMode(player.getUUID());
                        
                        if (state != null) {
                            // Teleport back
                            player.teleportTo(
                                state.startPosition().x,
                                state.startPosition().y,
                                state.startPosition().z
                            );
                        }
                        
                        // Reset gamemode
                        player.setGameMode(GameType.SURVIVAL);
                        player.getAbilities().mayfly = false;
                        player.getAbilities().flying = false;
                        player.getAbilities().invulnerable = false;
                        player.onUpdateAbilities();
                        
                        MutableComponent message = Component.translatable("limitedspectator.command.survival.activated")
                            .withStyle(ChatFormatting.GREEN);
                        
                        displayMessage(player,message, config.useActionBarMessages);
                        
                        return 1;
                    } catch (Exception e) {
                        LOGGER.error("Error executing /survival command", e);
                        ctx.getSource().sendFailure(Component.literal("§cInternal error: " + e.getMessage()));
                        return 0;
                    }
                })
            );
        });
    }
    
    private void registerEvents() {
        CommonConfig config = CommonConfig.getInstance();
        
        // Server tick - distance validation
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!spectatorManager.getConfig().isDistanceLimitEnabled()) return;
            
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                DistanceValidationResult result = spectatorManager.validateDistance(
                    player.getUUID(),
                    player.position()
                );
                
                if (result.isExceeded()) {
                    var targetPos = result.targetPosition();
                    
                    if (config.teleportBackOnExceed) {
                        // Teleport back to start position
                        player.teleportTo(targetPos.x, targetPos.y, targetPos.z);
                        displayMessage(player,
                            Component.translatable("limitedspectator.error.distance_exceeded").withStyle(ChatFormatting.RED),
                            config.useActionBarMessages
                        );
                    } else {
                        // Stop at boundary
                        var currentPos = player.position();
                        var direction = currentPos.subtract(targetPos).normalize();
                        var boundaryPos = targetPos.add(direction.scale(config.maxDistance));
                        player.teleportTo(boundaryPos.x, boundaryPos.y, boundaryPos.z);
                        
                        if (config.showDistanceWarnings) {
                            displayMessage(player,
                                Component.translatable("limitedspectator.error.distance_reached").withStyle(ChatFormatting.RED),
                                config.useActionBarMessages
                            );
                        }
                    }
                }
            }
        });
        
        // Player disconnect - cleanup
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            
            if (spectatorManager.isInSpectatorMode(player.getUUID())) {
                SpectatorState state = spectatorManager.exitSpectatorMode(player.getUUID());
                
                if (state != null && config.resetPositionOnLogout) {
                    player.teleportTo(
                        state.startPosition().x,
                        state.startPosition().y,
                        state.startPosition().z
                    );
                }
                
                player.setGameMode(GameType.SURVIVAL);
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.getAbilities().invulnerable = false;
                player.onUpdateAbilities();
                
                LOGGER.info("{} left the server. Spectator mode automatically disabled.", player.getName().getString());
            }
        });
    }
    
    private void registerLifecycleEvents() {
        // Start config watcher when server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (configWatcher != null) {
                configWatcher.start();
                LOGGER.info("Config hot-reload enabled");
            }
        });
        
        // Stop config watcher when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (configWatcher != null) {
                configWatcher.stop();
            }
        });
        
        // Hook into /reload command
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success && configWatcher != null) {
                configWatcher.reload();
                LOGGER.info("Configuration reloaded via /reload command");
            }
        });
    }
    
    public static SpectatorManager getSpectatorManager() {
        return spectatorManager;
    }
}
