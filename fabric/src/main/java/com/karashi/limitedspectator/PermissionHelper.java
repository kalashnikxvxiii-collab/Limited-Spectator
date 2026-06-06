package com.karashi.limitedspectator;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Permission-check helper for Fabric.
 *
 * <p>Pre-26.1 history: this class used to wrap {@code source.hasPermission(int)} in a
 * try/catch + reflection chain because the obfuscated method signature shifted
 * between MC 1.21.1 and 1.21.11, throwing {@code NoSuchMethodError} on mismatch.
 *
 * <p>26.1: Minecraft now ships with official, stable Mojang mappings, AND the
 * old integer-level system was replaced by the new {@link PermissionSet}/
 * {@code Permission} API. The reflection dance is no longer needed — and
 * wouldn't work anyway, since the method it tried to call doesn't exist.
 *
 * <p>The full migration to per-permission named checks is a larger refactor;
 * for the 3.0.0 hotpath we preserve the same semantics by mapping:
 * <ul>
 *   <li>level &le; 0 (everyone): always allowed</li>
 *   <li>level &ge; 1 (op territory): only the server console / a player with
 *       {@code ALL_PERMISSIONS}</li>
 * </ul>
 *
 * <p>TODO(mc-26.1): replace with proper named-permission checks once the
 * loader-specific permission story stabilises.
 */
public class PermissionHelper {

    public static boolean hasPermission(CommandSourceStack source, int level) {
        if (level <= 0) return true;
        return source.permissions() == PermissionSet.ALL_PERMISSIONS;
    }

    public static boolean checkPermission(CommandSourceStack source, boolean requireOp, int permissionLevel) {
        if (requireOp) {
            return hasPermission(source, 2);
        }
        return hasPermission(source, permissionLevel);
    }
}
