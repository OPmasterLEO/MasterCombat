package net.opmasterleo.combat.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class SuperVanishManager {

    private Plugin vanishPlugin;
    private static boolean vanishApiAvailable = false;
    private static Method isInvisibleMethod;

    public SuperVanishManager() {
        try {
            // Add support for PremiumVanish as well
            Plugin superVanish = Bukkit.getPluginManager().getPlugin("SuperVanish");
            Plugin premiumVanish = Bukkit.getPluginManager().getPlugin("PremiumVanish");
            if (superVanish != null && superVanish.isEnabled()) {
                this.vanishPlugin = superVanish;
            } else if (premiumVanish != null && premiumVanish.isEnabled()) {
                this.vanishPlugin = premiumVanish;
            } else {
                this.vanishPlugin = null;
                vanishApiAvailable = false;
                return;
            }
            
            try {
                Class<?> vanishAPI = Class.forName("de.myzelyam.api.vanish.VanishAPI");
                isInvisibleMethod = vanishAPI.getMethod("isInvisible", Player.class);
                vanishApiAvailable = true;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                vanishApiAvailable = false;
            }
        } catch (Exception e) {
            this.vanishPlugin = null;
            vanishApiAvailable = false;
        }
    }

    public boolean isVanished(Player player) {
        if (!vanishApiAvailable || vanishPlugin == null || !vanishPlugin.isEnabled() || player == null) {
            return false;
        }
        
        try {
            return (boolean) isInvisibleMethod.invoke(null, player);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return false;
        }
    }

    public boolean isVanishLoaded() {
        return vanishPlugin != null && vanishPlugin.isEnabled();
    }
}