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
        return plugin.isDisableElytra() && plugin.isInCombat(player);
    }

    private void sendBlocked(Player player) {
        String msg = plugin.getElytraDisabledMsg();
        String type = plugin.getElytraDisabledType();
        if (msg == null || msg.isEmpty()) return;
        switch (type == null ? "chat" : type.toLowerCase()) {
            case "actionbar" -> player.sendActionBar(ChatUtil.parse(plugin.getPrefix() + msg));
            case "title" -> player.showTitle(net.kyori.adventure.title.Title.title(
                    ChatUtil.parse(plugin.getPrefix() + msg), ChatUtil.parse(" ")));
            default -> player.sendMessage(ChatUtil.parse(plugin.getPrefix() + msg));
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

    @SuppressWarnings("ConstantConditions")
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

    @SuppressWarnings("ConstantConditions")
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
