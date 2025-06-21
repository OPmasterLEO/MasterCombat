package net.opmasterleo.combat.listener;

import java.util.Collection;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

import net.opmasterleo.combat.Combat;

public class EndCrystalListener implements Listener {
    private final ProtocolManager protocolManager;

    public EndCrystalListener() {
        protocolManager = ProtocolLibrary.getProtocolManager();
        setupPacketListeners();
    }

    private void setupPacketListeners() {
        protocolManager.addPacketListener(new PacketAdapter(Combat.getInstance(), ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.USE_ENTITY) {
                    Bukkit.getScheduler().runTask(Combat.getInstance(), () -> {
                        Entity entity = event.getPacket().getEntityModifier(event).read(0);
                        if (entity != null && entity.getType() == EntityType.END_CRYSTAL) {
                            Player player = event.getPlayer();
                            Combat.getInstance().registerCrystalPlacer(entity, player);
                        }
                    });
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!Combat.getInstance().getConfig().getBoolean("link-end-crystals", true)) {
            return;
        }

        Entity damaged = event.getEntity();
        Entity damager = event.getDamager();

        if (damager.getType() == EntityType.END_CRYSTAL) {
            if (handleCrystalDamageWithEvent(damaged, damager, event)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean handleCrystalDamageWithEvent(Entity damaged, Entity damager, EntityDamageByEntityEvent event) {
        Player damagedPlayer = (damaged instanceof Player) ? (Player) damaged : null;
        Player placer = Combat.getInstance().getCrystalManager().getPlacer(damager);
        if (damagedPlayer != null) {
            if (placer != null) {
                handleCombat(damagedPlayer, placer);
            } else {
                linkCrystalByProximity(damager, damagedPlayer);
            }
        }
        return false;
    }

    private void handleCombat(Player damagedPlayer, Player placer) {
        if (damagedPlayer.equals(placer)) {
            if (Combat.getInstance().getConfig().getBoolean("self-combat", false)) {
                Combat.getInstance().setCombat(damagedPlayer, damagedPlayer);
            }
            return;
        }
        Combat.getInstance().setCombat(damagedPlayer, placer);
        Combat.getInstance().setCombat(placer, damagedPlayer);
    }

    private void linkCrystalByProximity(Entity crystal, Player damagedPlayer) {
        Location crystalLocation = crystal.getLocation();
        World world = crystalLocation.getWorld();
        if (world == null) return;

        Collection<Entity> nearbyEntities = world.getNearbyEntities(crystalLocation, 4.0, 4.0, 4.0);
        for (Entity entity : nearbyEntities) {
            if (entity instanceof Player placer) {
                if (damagedPlayer.equals(placer) && !Combat.getInstance().getConfig().getBoolean("self-combat", false)) {
                    break;
                }
                Combat.getInstance().getCrystalManager().setPlacer(crystal, placer);
                Combat.getInstance().setCombat(damagedPlayer, placer);
                Combat.getInstance().setCombat(placer, damagedPlayer);
                break;
            }
        }
    }

    public void registerCrystalPlacer(Entity crystal, Player placer) {
        Combat.getInstance().getCrystalManager().setPlacer(crystal, placer);
    }
}
