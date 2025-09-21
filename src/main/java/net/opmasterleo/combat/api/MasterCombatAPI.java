package net.opmasterleo.combat.api;

import java.util.UUID;

public interface MasterCombatAPI {
    /**
     * Tag a player for combat.
     *
     * @param uuid the player's UUID
     */
    void tagPlayer(UUID uuid);

    /**
     * Untag a player from combat.
     *
     * @param uuid the player's UUID
     */
    void untagPlayer(UUID uuid);

    /**
     * New API: return "Fighting" when in combat, "Idle" otherwise
     */
    String getMasterCombatState(UUID uuid);

    /**
     * New API: true if the plugin's glow system treats the player as glowing
     */
    boolean isPlayerGlowing(UUID uuid);

    /**
     * Combined helper: state plus glowing annotation, e.g. "Fighting (Glowing)" or "Idle"
     */
    String getMasterCombatStateWithGlow(UUID uuid);

    /**
     * Get the remaining combat time for a player in seconds.
     * 
     * @param uuid the player's UUID
     * @return remaining combat time in seconds, or 0 if not in combat
     */
    int getRemainingCombatTime(UUID uuid);

    /**
     * Get the total combat duration for a player.
     * 
     * @param uuid the player's UUID
     * @return total combat duration in seconds since joining the server
     */
    long getTotalCombatTime(UUID uuid);

    /**
     * Get the opponent's UUID if player is in combat.
     * 
     * @param uuid the player's UUID
     * @return the opponent's UUID, or null if not in combat
     */
    UUID getCombatOpponent(UUID uuid);

    /**
     * Check if the plugin is actively monitoring combat.
     * 
     * @return true if combat system is enabled
     */
    boolean isCombatSystemEnabled();

    /**
     * Get the number of active combat-tagged players.
     * 
     * @return the count of players currently in combat
     */
    int getActiveCombatCount();
}