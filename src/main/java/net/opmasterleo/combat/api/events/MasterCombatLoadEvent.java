package net.opmasterleo.combat.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class MasterCombatLoadEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    public MasterCombatLoadEvent() {
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }
}