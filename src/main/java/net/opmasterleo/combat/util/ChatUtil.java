package net.opmasterleo.combat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatUtil {
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

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
            return Component.empty();
        }

        if (message.contains("<") && message.contains(">")) {
            try {
                return MINI_MESSAGE.deserialize(message);
            } catch (Exception e) {
            }
        }

        String processed = message.replace('&', '§');
        Matcher matcher = HEX_COLOR_PATTERN.matcher(processed);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        try {
            return LegacyComponentSerializer.legacySection().deserialize(sb.toString());
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

    public static String toPlainText(String input) {
        if (input == null || input.isEmpty()) return "";
        input = input.replaceAll("&#[A-Fa-f0-9]{6}", "");
        input = input.replaceAll("§x§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]§[A-Fa-f0-9]", "");
        input = input.replaceAll("&x&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]&[A-Fa-f0-9]", "");
        input = input.replaceAll("[§&][0-9a-fk-or]", "");
        
        return input;
    }

    public static String toConsoleFormat(String input) {
        if (input == null || input.isEmpty()) return "";
        input = input.replace('&', '§');
        Matcher matcher = HEX_COLOR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        input = sb.toString();
        
        return input;
    }
}