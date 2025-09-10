package net.opmasterleo.combat.util;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtil {
    
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)([smhd])");
    
    public static long parseTimeToMillis(String timeString) {
        if (timeString == null || timeString.isEmpty()) {
            return 0;
        }
        
        try {
            return TimeUnit.SECONDS.toMillis(Long.parseLong(timeString));
        } catch (NumberFormatException ignored) {
        }
        
        Matcher matcher = TIME_PATTERN.matcher(timeString);
        if (matcher.matches()) {
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            
            switch (unit) {
                case "s" -> {
                    return TimeUnit.SECONDS.toMillis(value);
                }
                case "m" -> {
                    return TimeUnit.MINUTES.toMillis(value);
                }
                case "h" -> {
                    return TimeUnit.HOURS.toMillis(value);
                }
                case "d" -> {
                    return TimeUnit.DAYS.toMillis(value);
                }
            }
        }
        
        return 0;
    }
    public static String formatTime(long millis) {
        if (millis < 0) {
            return "0s";
        }
        
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        
        StringBuilder builder = new StringBuilder();
        
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0) {
            builder.append(minutes).append("m ");
        }
        if (seconds > 0 || builder.length() == 0) {
            builder.append(seconds).append("s");
        }
        
        return builder.toString().trim();
    }
}
