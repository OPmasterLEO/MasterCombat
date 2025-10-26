package net.opmasterleo.combat.listener.player;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import net.opmasterleo.combat.Combat;
import net.opmasterleo.combat.util.ChatUtil;

public class ElytraDisableListener implements Listener {
    private final Combat plugin;

    public ElytraDisableListener(Combat plugin) {
        this.plugin = plugin;
    }

    private boolean isActive(Player player) {
        if (!plugin.isInCombat(player)) return false;
        if (plugin.isDisableElytra()) return true;
        if (plugin.getConfig().getBoolean("item_restrictions.enabled", false)) {
            for (String item : plugin.getConfig().getStringList("item_restrictions.disabled_items")) {
                if (item.equalsIgnoreCase("ELYTRA")) {
                    return true;
                }
            }
        }
        
        return false;
    }

    private void sendBlocked(Player player) {
        if (plugin.getConfig().getBoolean("item_restrictions.enabled", false)) {
            for (String item : plugin.getConfig().getStringList("item_restrictions.disabled_items")) {
                if (item.equalsIgnoreCase("ELYTRA")) {
                    String msg = plugin.getConfig().getString("item_restrictions.text", 
                        plugin.getConfig().getString("Messages.Prefix", "") + "&cYou cannot use this item while in combat!");
                    String type = plugin.getConfig().getString("item_restrictions.type", "actionbar");
                    sendMessage(player, msg, type);
                    return;
                }
            }
        }

        String msg = plugin.getElytraDisabledMsg();
        String type = plugin.getElytraDisabledType();
        if (msg == null || msg.isEmpty()) return;
        sendMessage(player, plugin.getPrefix() + msg, type);
    }
    
    private void sendMessage(Player player, String message, String type) {
        switch (type == null ? "chat" : type.toLowerCase()) {
            case "actionbar" -> player.sendActionBar(ChatUtil.parse(message));
            case "title" -> player.showTitle(net.kyori.adventure.title.Title.title(
                    ChatUtil.parse(message), ChatUtil.parse(" ")));
            case "both" -> {
                player.sendMessage(ChatUtil.parse(message));
                player.sendActionBar(ChatUtil.parse(message));
            }
            default -> player.sendMessage(ChatUtil.parse(message));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isActive(player)) return;
        if (!event.isGliding()) return;
        event.setCancelled(true);
        sendBlocked(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isActive(player)) return;

    ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if (event.isShiftClick() && current != null && current.getType() == Material.ELYTRA) {
            event.setCancelled(true);
            sendBlocked(player);
            return;
        }

        if (cursor.getType() == Material.ELYTRA && event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
            sendBlocked(player);
            return;
        }

        if (event.getClick() == ClickType.NUMBER_KEY && event.getSlotType() == InventoryType.SlotType.ARMOR) {
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0 && hotbar < player.getInventory().getSize()) {
                ItemStack hotbarItem = player.getInventory().getItem(hotbar);
                if (hotbarItem != null && hotbarItem.getType() == Material.ELYTRA) {
                    event.setCancelled(true);
                    sendBlocked(player);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isActive(player)) return;

    ItemStack item = event.getOldCursor();
    if (item.getType() != Material.ELYTRA) return;

        InventoryView view = event.getView();
        for (int rawSlot : event.getRawSlots()) {
            InventoryType.SlotType type = view.getSlotType(rawSlot);
            if (type == InventoryType.SlotType.ARMOR) {
                event.setCancelled(true);
                sendBlocked(player);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRightClickEquip(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isActive(player)) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.ELYTRA) {
            event.setCancelled(true);
            sendBlocked(player);
        }
    }
}
