package com.jarvis.tools.location;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Maps direction links ("Maps URLs"): free, no API key, open turn-by-turn navigation in the Google Maps app on
 * a phone. A link carries at most {@value #MAX_WAYPOINTS} stops between origin and destination, so a longer day is
 * split into consecutive legs, each one starting where the previous one ended.
 */
public final class GoogleMapsLinks {

    /** Google documents up to 9 waypoints per link. */
    public static final int MAX_WAYPOINTS = 9;

    private GoogleMapsLinks() {
    }

    /**
     * Links for driving start → stops (in this order) → end.
     *
     * @param start address where the day starts
     * @param stops addresses in visit order
     * @param end address where the day ends (usually the start again)
     * @return one link, or several consecutive legs for a long day
     */
    public static List<String> route(String start, List<String> stops, String end) {
        List<String> links = new ArrayList<>();
        List<String> points = new ArrayList<>();
        points.add(start);
        points.addAll(stops);
        points.add(end);
        int from = 0;
        while (from < points.size() - 1) {
            int to = Math.min(points.size() - 1, from + MAX_WAYPOINTS + 1);
            links.add(link(points.get(from), points.subList(from + 1, to), points.get(to)));
            from = to;
        }
        return links;
    }

    private static String link(String origin, List<String> waypoints, String destination) {
        StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving")
                .append("&origin=").append(encode(origin))
                .append("&destination=").append(encode(destination));
        if (!waypoints.isEmpty()) {
            url.append("&waypoints=").append(encode(String.join("|", waypoints)));
        }
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value.strip(), StandardCharsets.UTF_8);
    }
}
