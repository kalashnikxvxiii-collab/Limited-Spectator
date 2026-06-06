package com.karashi.limitedspectator;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core spectator management logic - loader-agnostic business logic.
 * This class handles the core functionality for spectator mode without
 * any dependencies on specific mod loaders.
 * 
 * Thread-safe for concurrent access.
 */
public class SpectatorManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpectatorManager.class);
    
    private final Map<UUID, SpectatorState> spectatorStates = new HashMap<>();
    private final SpectatorConfig config;
    
    public SpectatorManager(SpectatorConfig config) {
        this.config = config;
    }
    
    /**
     * Registers a player as entering spectator mode.
     * 
     * @param playerUUID The player's UUID
     * @param startPosition The position where spectator mode was activated
     * @param startDimension The dimension where spectator mode was activated
     */
    public synchronized void enterSpectatorMode(UUID playerUUID, Vec3 startPosition, ResourceKey<Level> startDimension) {
        SpectatorState state = SpectatorState.create(startPosition, startDimension);
        spectatorStates.put(playerUUID, state);
        LOGGER.debug("Player {} entered spectator mode at {} in dimension {}", 
            playerUUID, startPosition, startDimension.identifier());
    }
    
    /**
     * Unregisters a player from spectator mode.
     * 
     * @param playerUUID The player's UUID
     * @return The spectator state if the player was in spectator mode, null otherwise
     */
    public synchronized SpectatorState exitSpectatorMode(UUID playerUUID) {
        SpectatorState state = spectatorStates.remove(playerUUID);
        if (state != null) {
            LOGGER.debug("Player {} exited spectator mode after {} ms", 
                playerUUID, state.getDuration());
        }
        return state;
    }
    
    /**
     * Checks if a player is currently in spectator mode.
     * 
     * @param playerUUID The player's UUID
     * @return true if the player is in spectator mode
     */
    public synchronized boolean isInSpectatorMode(UUID playerUUID) {
        return spectatorStates.containsKey(playerUUID);
    }
    
    /**
     * Gets the spectator state for a player.
     * 
     * @param playerUUID The player's UUID
     * @return The spectator state, or null if not in spectator mode
     */
    public synchronized SpectatorState getSpectatorState(UUID playerUUID) {
        return spectatorStates.get(playerUUID);
    }
    
    /**
     * Validates if a player's current position exceeds the maximum allowed distance.
     * 
     * @param playerUUID The player's UUID
     * @param currentPosition The player's current position
     * @return A validation result indicating if the distance is exceeded
     */
    public synchronized DistanceValidationResult validateDistance(UUID playerUUID, Vec3 currentPosition) {
        if (!config.isDistanceLimitEnabled()) {
            return DistanceValidationResult.noLimit();
        }
        
        SpectatorState state = spectatorStates.get(playerUUID);
        if (state == null) {
            return DistanceValidationResult.notInSpectatorMode();
        }
        
        double distance = currentPosition.distanceTo(state.startPosition());
        double maxDistance = config.getMaxDistance();
        
        if (distance > maxDistance) {
            return DistanceValidationResult.exceeded(distance, maxDistance, state.startPosition());
        }
        
        return DistanceValidationResult.withinLimit(distance, maxDistance);
    }
    
    /**
     * Gets the current configuration.
     * 
     * @return The spectator configuration
     */
    public SpectatorConfig getConfig() {
        return config;
    }
    
    /**
     * Clears all spectator states. Used for cleanup.
     */
    public synchronized void clearAll() {
        spectatorStates.clear();
        LOGGER.debug("Cleared all spectator states");
    }
    
    /**
     * Gets the number of players currently in spectator mode.
     * 
     * @return The count of spectators
     */
    public synchronized int getSpectatorCount() {
        return spectatorStates.size();
    }
}
