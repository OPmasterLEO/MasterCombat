package net.opmasterleo.combat.listener.player;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {
    private static final String LOGOUT_KEY = "Messages.LogoutInCombat";
    private static final String COMBAT_LOGGED_KEY = "Messages.CombatLogged";
    private static final String PREFIX_KEY = "Messages.Prefix";
    private static final String PLAYER_PLACEHOLDER = "%player%";

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();
        
        if (!combat.isInCombat(player)) return;

        Player opponent = combat.getCombatOpponent(player);
        if (opponent != null) {
            player.setKiller(opponent);
        }

        player.setHealth(0);
        String logoutMsg = getCombatMessage(combat, LOGOUT_KEY);
        
        if (!logoutMsg.isEmpty()) {
            String prefix = combat.getMessage(PREFIX_KEY);
            if (combat.getConfig().getBoolean("General.CustomDeathMessage.enabled", false)) {
                String customPrefix = combat.getConfig().getString("General.CustomDeathMessage.prefix", "");
                prefix = ChatUtil.parse(customPrefix).toString() + prefix;
            }
            String message = (prefix + logoutMsg).replace(PLAYER_PLACEHOLDER, player.getName());
            broadcastCombatMessage(combat, message);
        }

        combat.forceCombatCleanup(player.getUniqueId());
        
        if (opponent != null) {
            String combatLoggedMsg = getCombatMessage(combat, COMBAT_LOGGED_KEY);
            if (!combatLoggedMsg.isEmpty()) {
                String prefix = combat.getMessage(PREFIX_KEY);
                if (combat.getConfig().getBoolean("General.CustomDeathMessage.enabled", false)) {
                    String customPrefix = combat.getConfig().getString("General.CustomDeathMessage.prefix", "");
                    prefix = ChatUtil.parse(customPrefix).toString() + prefix;
                }
                String message = (prefix + combatLoggedMsg).replace(PLAYER_PLACEHOLDER, player.getName());
                opponent.sendMessage(ChatUtil.parse(message));
            }
        }
    }

    private String getCombatMessage(Combat combat, String key) {
        if (combat.getConfig().isConfigurationSection(key)) {
            return combat.getConfig().getString(key + ".text", "");
        }
        return combat.getConfig().getString(key, "");
    }

    private void broadcastCombatMessage(Combat combat, String message) {
        net.kyori.adventure.text.Component component = ChatUtil.parse(message);
        combat.getServer().getOnlinePlayers().forEach(p -> p.sendMessage(component));
    }
}