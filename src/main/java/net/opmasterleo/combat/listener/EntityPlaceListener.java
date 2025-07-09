package net.opmasterleo.combat.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import net.opmasterleo.combat.Combat;

public class EntityPlaceListener implements Listener {

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;

        Block block = event.getBlock();
        Player player = event.getPlayer();
        Combat combat = Combat.getInstance();

        if (block.getType() == Material.END_CRYSTAL) {
            Entity crystal = block.getWorld().spawnEntity(block.getLocation(), EntityType.END_CRYSTAL);
            combat.registerCrystalPlacer(crystal, player);
        }
    }
}
