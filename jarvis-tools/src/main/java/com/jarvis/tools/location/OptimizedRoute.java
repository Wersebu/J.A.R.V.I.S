package com.jarvis.tools.location;

import java.util.List;

/**
 * Result of {@link RouteOptimizer#optimize}: a visiting order over matrix indices, its total cost,
 * and any indices that had to be excluded because they were unreachable from the rest of the matrix.
 *
 * @param visitOrder matrix indices in visiting order, starting with the start index
 * @param totalCost sum of the chosen optimization criterion (distance or duration) along the route
 * @param unreachableIndices matrix indices that could not be connected and were excluded
 */
public record OptimizedRoute(List<Integer> visitOrder, double totalCost, List<Integer> unreachableIndices) {
}
