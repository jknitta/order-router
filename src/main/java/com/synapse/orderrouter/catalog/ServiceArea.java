package com.synapse.orderrouter.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The ZIP codes a supplier serves, parsed from the service_zips column. */
public final class ServiceArea {

    // 4 digits are accepted because the data lost leading zeros (2164 is really 02164).
    private static final String ZIP = "\\d{4,5}";
    private static final Pattern SINGLE = Pattern.compile(ZIP);
    private static final Pattern RANGE = Pattern.compile("(" + ZIP + ")-(" + ZIP + ")");

    private final List<int[]> ranges;

    private ServiceArea(List<int[]> ranges) {
        this.ranges = ranges;
    }

    /** Each comma-separated token is a single ZIP or a range; anything else is ignored. */
    public static ServiceArea parse(String raw) {
        List<int[]> ranges = new ArrayList<>();
        if (raw != null) {
            for (String token : raw.split(",")) {
                parseToken(token.trim()).ifPresent(ranges::add);
            }
        }
        return new ServiceArea(ranges);
    }

    private static Optional<int[]> parseToken(String token) {
        if (SINGLE.matcher(token).matches()) {
            int zip = Integer.parseInt(token);
            return Optional.of(new int[] {zip, zip});
        }
        Matcher range = RANGE.matcher(token);
        if (range.matches()) {
            int low = Integer.parseInt(range.group(1));
            int high = Integer.parseInt(range.group(2));
            return low <= high ? Optional.of(new int[] {low, high}) : Optional.empty();
        }
        return Optional.empty();
    }

    /** Returns the 5-digit form of a 4- or 5-digit ZIP, or empty if it is not a ZIP. */
    public static Optional<String> normalize(String zip) {
        if (zip == null || !SINGLE.matcher(zip).matches()) {
            return Optional.empty();
        }
        return Optional.of("0".repeat(5 - zip.length()) + zip);
    }

    public boolean serves(String zip) {
        return normalize(zip)
                .map(Integer::parseInt)
                .map(value -> ranges.stream().anyMatch(range -> value >= range[0] && value <= range[1]))
                .orElse(false);
    }
}
