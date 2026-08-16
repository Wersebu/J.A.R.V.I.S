package com.jarvis.tools.location;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * Configuration for the geocoding/routing capability. Defaults point at free, public instances
 * (OpenStreetMap Nominatim for geocoding, OSRM's public demo server for routing) so the tool works
 * out of the box without a paid API key - both base URLs are swappable to a self-hosted instance
 * without any code change, since {@link NominatimGeocodingClient}/{@link OsrmRoutingClient} only
 * ever read them from here.
 *
 * @param enabled whether the location capability is available to the tool runtime
 * @param nominatimBaseUrl Nominatim-compatible geocoding base URL
 * @param osrmBaseUrl OSRM-compatible routing base URL
 * @param userAgent descriptive User-Agent sent to the geocoding provider - Nominatim's usage
 *        policy requires an application-identifying value, not a generic library default
 * @param minGeocodeIntervalMillis minimum delay between sequential geocode calls, to stay under
 *        Nominatim's public-instance rate policy (~1 request/second) during a batch geocode
 * @param maxBatchSize maximum addresses/points accepted in one GEOCODE/ROUTE_MATRIX/OPTIMIZE_ROUTE call
 * @param exactOptimizationMaxStops maximum stop count (excluding the start point) for which
 *        {@link RouteOptimizer} uses exact brute-force search instead of the nearest-neighbour +
 *        2-opt heuristic
 * @param connectTimeout connection timeout
 * @param readTimeout request/read timeout
 * @param geocodeCandidateLimit how many candidates to request per geocode query for
 *        {@link GeocodeCandidateScorer} to evaluate - must be more than 1 for postal-code/address
 *        validation to have anything to choose between, per candidate, in a single HTTP call
 */
@ConfigurationProperties(prefix = "jarvis.location")
public record LocationProperties(
        Boolean enabled,
        String nominatimBaseUrl,
        String osrmBaseUrl,
        String userAgent,
        int minGeocodeIntervalMillis,
        int maxBatchSize,
        int exactOptimizationMaxStops,
        Duration connectTimeout,
        Duration readTimeout,
        int geocodeCandidateLimit
) {

    /**
     * Applies safe defaults.
     */
    @ConstructorBinding
    public LocationProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        nominatimBaseUrl = nominatimBaseUrl == null || nominatimBaseUrl.isBlank()
                ? "https://nominatim.openstreetmap.org" : stripTrailingSlash(nominatimBaseUrl);
        osrmBaseUrl = osrmBaseUrl == null || osrmBaseUrl.isBlank()
                ? "https://router.project-osrm.org" : stripTrailingSlash(osrmBaseUrl);
        userAgent = userAgent == null || userAgent.isBlank() ? "JARVIS-Core-LocationTool/1.0" : userAgent.strip();
        minGeocodeIntervalMillis = minGeocodeIntervalMillis > 0 ? minGeocodeIntervalMillis : 1100;
        maxBatchSize = maxBatchSize > 0 ? maxBatchSize : 25;
        exactOptimizationMaxStops = exactOptimizationMaxStops > 0 ? exactOptimizationMaxStops : 8;
        connectTimeout = connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                ? Duration.ofSeconds(8) : readTimeout;
        geocodeCandidateLimit = geocodeCandidateLimit > 0 ? geocodeCandidateLimit : 5;
    }

    /**
     * Returns whether the capability is enabled.
     *
     * @return true when enabled
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    private static String stripTrailingSlash(String value) {
        String current = value.strip();
        while (current.endsWith("/")) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }
}
