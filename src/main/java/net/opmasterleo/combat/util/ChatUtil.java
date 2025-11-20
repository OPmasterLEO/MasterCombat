package net.opmasterleo.combat.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class ChatUtil {
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Component EMPTY_COMPONENT = Component.empty();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
        .tags(TagResolver.builder()
            .resolver(StandardTags.color())
            .resolver(StandardTags.decorations())
            .resolver(StandardTags.gradient())
            .resolver(StandardTags.rainbow())
            .build())
        .build();

    public static Component parse(String message) {
        if (message == null || message.isEmpty()) {
            return EMPTY_COMPONENT;
        }
        
        final int length = message.length();
        if (length > 500) {
            return Component.text(message.substring(0, 500));
        }

        final boolean hasAmpersand = message.indexOf('&') != -1;
        final boolean hasMiniMsg = message.indexOf('<') != -1 && message.indexOf('>') != -1;
        final boolean hasSection = message.indexOf('§') != -1;
        
        if (!hasAmpersand && !hasMiniMsg && !hasSection) {
            return Component.text(message);
        }

        if (hasMiniMsg) {
            try {
                return MINI_MESSAGE.deserialize(message);
            } catch (Exception e) {
            }
        }

        if (!hasAmpersand && !hasSection) {
            return Component.text(message);
        }

        String processed = hasAmpersand ? message.replace('&', '§') : message;
        final boolean hasHex = processed.indexOf('#') != -1;
        
        if (!hasHex) {
            try {
                return LEGACY_SERIALIZER.deserialize(processed);
            } catch (Exception e) {
                return Component.text(message).color(NamedTextColor.WHITE);
            }
        }

        Matcher matcher = HEX_COLOR_PATTERN.matcher(processed);
        if (!matcher.find()) {
            try {
                return LEGACY_SERIALIZER.deserialize(processed);
            } catch (Exception e) {
                return Component.text(message).color(NamedTextColor.WHITE);
            }
        }
        
        matcher.reset();
        StringBuilder sb = new StringBuilder(length + 80);
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(processed, lastEnd, matcher.start());
            String hex = matcher.group(1);
            sb.append("§x");
            for (int i = 0; i < 6; i++) {
                sb.append('§').append(hex.charAt(i));
            }
            lastEnd = matcher.end();
        }
        sb.append(processed, lastEnd, length);
        
        try {
            return LEGACY_SERIALIZER.deserialize(sb.toString());
        } catch (Exception e) {
            return Component.text(message).color(NamedTextColor.WHITE);
        }
    }

    public static String serializeMini(Component component) {
        return MINI_MESSAGE.serialize(component);
    }

    public static Component parseMini(String message) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(message);
        } catch (Exception e) {
            return Component.text(message).color(NamedTextColor.WHITE);
        }
    }

    public static Component parseMini(String message, TagResolver... resolvers) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(message, resolvers);
        } catch (Exception e) {
            return Component.text(message).color(NamedTextColor.WHITE);
        }
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

    private static final Pattern ALL_COLOR_CODES = Pattern.compile(
        "&#[A-Fa-f0-9]{6}|§x(?:§[A-Fa-f0-9]){6}|&x(?:&[A-Fa-f0-9]){8}|[§&][0-9a-fk-or]"
    );
    
    public static String toPlainText(String input) {
        if (input == null || input.isEmpty()) return "";
        return ALL_COLOR_CODES.matcher(input).replaceAll("");
    }

    public static String toConsoleFormat(String input) {
        if (input == null || input.isEmpty()) return "";
        
        String processed = input.replace('&', '§');
        Matcher matcher = HEX_COLOR_PATTERN.matcher(processed);
        if (!matcher.find()) {
            return processed;
        }

        matcher.reset();
        StringBuilder sb = new StringBuilder(processed.length() + 32);
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(processed, lastEnd, matcher.start());
            String hex = matcher.group(1);
            sb.append("§x");
            for (int i = 0; i < 6; i++) {
                sb.append("§").append(hex.charAt(i));
            }
            lastEnd = matcher.end();
        }
        sb.append(processed, lastEnd, processed.length());
        
        return sb.toString();
    }
}