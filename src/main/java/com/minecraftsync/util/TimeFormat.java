package com.minecraftsync.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class TimeFormat {

    private static final DateTimeFormatter FULL =
            DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm", Locale.FRANCE).withZone(ZoneId.systemDefault());

    private TimeFormat() {
    }

    /** « il y a 5 minutes », « il y a 2 heures »… */
    public static String relative(Instant instant) {
        if (instant == null) return "jamais";
        Duration d = Duration.between(instant, Instant.now());
        if (d.isNegative() || d.toSeconds() < 60) return "à l'instant";
        long minutes = d.toMinutes();
        if (minutes < 60) return "il y a " + minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = d.toHours();
        if (hours < 24) return "il y a " + hours + (hours == 1 ? " heure" : " heures");
        long days = d.toDays();
        if (days < 30) return "il y a " + days + (days == 1 ? " jour" : " jours");
        return "le " + FULL.format(instant);
    }

    public static String full(Instant instant) {
        return instant == null ? "inconnue" : FULL.format(instant);
    }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " o";
        double v = bytes / 1024.0;
        String[] units = {"Ko", "Mo", "Go", "To"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.FRANCE, "%.1f %s", v, units[i]);
    }
}
