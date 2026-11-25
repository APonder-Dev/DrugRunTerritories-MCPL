/*
 * DrugRunTerritories - Copyright (c) Anthony Ponder / APonder.dev
 */
package dev.aponder.drugrun.territories.territory;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/** Daily capture window in server time. A window whose end is before its start wraps past midnight. */
public record TimeWindow(LocalTime start, LocalTime end) {

    /** Parses {@code "HH:mm-HH:mm"}. */
    public static TimeWindow parse(String text) {
        String[] parts = text.trim().split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected HH:mm-HH:mm, got " + text);
        }
        try {
            return new TimeWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Expected HH:mm-HH:mm, got " + text, e);
        }
    }

    public boolean contains(LocalTime time) {
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }
}
