package net.opmasterleo.combat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class ChatUtil {
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        input = input.replaceAll("&([A-Fa-f0-9]{6})", "<#$1>");
        return LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .build()
                .deserialize(input);
    }
    
    public static TextColor getLastColor(Component component) {
        if (component instanceof TextComponent tc && tc.color() != null) {
            return tc.color();
        }
        for (Component child : component.children()) {
            TextColor color = getLastColor(child);
            if (color != null) return color;
        }
        return null;
    }

    public static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }
}