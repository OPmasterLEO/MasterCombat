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
}