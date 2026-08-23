package com.jarvis.tools.location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local, pure-Java route sequencing over an already-fetched distance/duration matrix - no network
 * I/O happens here at all, deliberately separate from {@link RoutingClient}. Given a start index
 * and a cost matrix (either distances or durations, chosen by the caller), finds a visiting order
 * for the remaining points that reasonably minimizes total cost:
 *
 * <ul>
 *   <li>Small instances (stop count at or below the configured exact-search threshold) use exact
 *       brute-force permutation search - guaranteed optimal, cheap at this size.</li>
 *   <li>Larger instances use a nearest-neighbour construction followed by 2-opt local-search
 *       improvement - a standard, reasonable heuristic, not a guaranteed optimum.</li>
 * </ul>
 *
 * <p>Points with no direct edge to/from the start (a {@code null} matrix cell) are excluded from
 * optimization entirely and reported back as unreachable, rather than crashing or being silently
 * treated as zero-cost.
 */
public class RouteOptimizer {

    private static final int MAX_TWO_OPT_ITERATIONS_PER_STOP = 200;

    /**
     * Finds a visiting order starting at {@code startIndex} over the remaining matrix indices - an
     * open path (never returns to {@code startIndex}). Equivalent to {@code optimize(matrix,
     * startIndex, exactSearchMaxStops, false)}.
     *
     * @param matrix cost matrix ({@code matrix[i][j]} = cost from point i to point j); {@code null}
     *               cells mean unreachable
     * @param startIndex index of the fixed starting point
     * @param exactSearchMaxStops stop count (excluding start) at or below which exact brute-force
     *                            search is used instead of the nearest-neighbour + 2-opt heuristic
     * @return the optimized route
     */
    public OptimizedRoute optimize(Double[][] matrix, int startIndex, int exactSearchMaxStops) {
        return optimize(matrix, startIndex, exactSearchMaxStops, false);
    }

    /**
     * Finds a visiting order starting at {@code startIndex} over the remaining matrix indices,
     * optionally targeting a closed loop (the trip back to {@code startIndex} counted as part of
     * the cost being minimized, and included in {@link OptimizedRoute#totalCost()}) - e.g. a Store
     * Audit day that must return to its fixed start point, not merely end at the last stop.
     *
     * <p>When {@code closeLoop} is true but the chosen order's return edge back to {@code
     * startIndex} is itself unreachable ({@code null} in the matrix), the search still proceeds
     * (that candidate order is simply not penalized for the missing return leg during comparison)
     * and the returned {@link OptimizedRoute#totalCost()} falls back to the open-path cost - never
     * fabricated. Callers that need the actual resolved return-leg distance/duration should read it
     * directly from the same distance/duration matrix this cost matrix was derived from.</p>
     *
     * @param matrix cost matrix ({@code matrix[i][j]} = cost from point i to point j); {@code null}
     *               cells mean unreachable
     * @param startIndex index of the fixed starting point
     * @param exactSearchMaxStops stop count (excluding start) at or below which exact brute-force
     *                            search is used instead of the nearest-neighbour + 2-opt heuristic
     * @param closeLoop whether the visiting order should be chosen to minimize the closed-loop cost
     *                  (including the trip back to {@code startIndex}) rather than the open-path cost
     * @return the optimized route
     */
    public OptimizedRoute optimize(Double[][] matrix, int startIndex, int exactSearchMaxStops, boolean closeLoop) {
        int size = matrix.length;
        List<Integer> reachable = new ArrayList<>();
        List<Integer> unreachable = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            if (index == startIndex) {
                continue;
            }
            Double toStop = matrix[startIndex][index];
            Double fromStop = matrix[index][startIndex];
            if (toStop == null || fromStop == null) {
                unreachable.add(index);
            } else {
                reachable.add(index);
            }
        }
        if (reachable.isEmpty()) {
            return new OptimizedRoute(List.of(startIndex), 0d, unreachable);
        }
        List<Integer> order = reachable.size() <= Math.max(0, exactSearchMaxStops)
                ? bruteForce(matrix, startIndex, reachable, closeLoop)
                : nearestNeighbourThenTwoOpt(matrix, startIndex, reachable, closeLoop);
        double totalCost = routeCost(matrix, startIndex, order, closeLoop);
        List<Integer> visitOrder = new ArrayList<>(order.size() + 1);
        visitOrder.add(startIndex);
        visitOrder.addAll(order);
        return new OptimizedRoute(visitOrder, totalCost, unreachable);
    }

    /**
     * Open-path cost, plus the return-to-{@code start} leg when {@code closeLoop} is true and that
     * leg is resolvable - falls back to the plain open-path cost (never penalizes/fabricates)
     * otherwise, so an order is never rejected purely because its particular last stop happens to
     * have no direct edge back to the start.
     */
    private double routeCost(Double[][] matrix, int start, List<Integer> order, boolean closeLoop) {
        double cost = pathCost(matrix, start, order);
        if (!closeLoop || order.isEmpty() || cost == Double.POSITIVE_INFINITY) {
            return cost;
        }
        Double returnEdge = matrix[order.get(order.size() - 1)][start];
        return returnEdge == null ? cost : cost + returnEdge;
    }

    private List<Integer> bruteForce(Double[][] matrix, int start, List<Integer> stops, boolean closeLoop) {
        List<Integer> working = new ArrayList<>(stops);
        Best best = new Best();
        permute(working, 0, matrix, start, closeLoop, best);
        return best.order == null ? working : best.order;
    }

    private void permute(List<Integer> arrangement, int fixedUpTo, Double[][] matrix, int start, boolean closeLoop, Best best) {
        if (fixedUpTo == arrangement.size()) {
            double cost = routeCost(matrix, start, arrangement, closeLoop);
            if (cost < best.cost) {
                best.cost = cost;
                best.order = new ArrayList<>(arrangement);
            }
            return;
        }
        for (int index = fixedUpTo; index < arrangement.size(); index++) {
            Collections.swap(arrangement, fixedUpTo, index);
            permute(arrangement, fixedUpTo + 1, matrix, start, closeLoop, best);
            Collections.swap(arrangement, fixedUpTo, index);
        }
    }

    private List<Integer> nearestNeighbourThenTwoOpt(Double[][] matrix, int start, List<Integer> stops, boolean closeLoop) {
        List<Integer> remaining = new ArrayList<>(stops);
        List<Integer> order = new ArrayList<>();
        int current = start;
        while (!remaining.isEmpty()) {
            int nearestPosition = 0;
            double nearestCost = Double.POSITIVE_INFINITY;
            for (int position = 0; position < remaining.size(); position++) {
                Double edge = matrix[current][remaining.get(position)];
                double cost = edge == null ? Double.POSITIVE_INFINITY : edge;
                if (cost < nearestCost) {
                    nearestCost = cost;
                    nearestPosition = position;
                }
            }
            int next = remaining.remove(nearestPosition);
            order.add(next);
            current = next;
        }
        return twoOpt(matrix, start, order, closeLoop);
    }

    private List<Integer> twoOpt(Double[][] matrix, int start, List<Integer> initialOrder, boolean closeLoop) {
        List<Integer> best = new ArrayList<>(initialOrder);
        double bestCost = routeCost(matrix, start, best, closeLoop);
        int maxIterations = MAX_TWO_OPT_ITERATIONS_PER_STOP * Math.max(1, best.size());
        int iterations = 0;
        boolean improved = true;
        while (improved && iterations < maxIterations) {
            improved = false;
            outer:
            for (int i = 0; i < best.size() - 1; i++) {
                for (int j = i + 1; j < best.size(); j++) {
                    List<Integer> candidate = reversedSegment(best, i, j);
                    double candidateCost = routeCost(matrix, start, candidate, closeLoop);
                    iterations++;
                    if (candidateCost < bestCost) {
                        best = candidate;
                        bestCost = candidateCost;
                        improved = true;
                        break outer;
                    }
                    if (iterations >= maxIterations) {
                        break outer;
                    }
                }
            }
        }
        return best;
    }

    private List<Integer> reversedSegment(List<Integer> order, int from, int to) {
        List<Integer> result = new ArrayList<>(order.subList(0, from));
        List<Integer> segment = new ArrayList<>(order.subList(from, to + 1));
        Collections.reverse(segment);
        result.addAll(segment);
        result.addAll(order.subList(to + 1, order.size()));
        return result;
    }

    private double pathCost(Double[][] matrix, int start, List<Integer> order) {
        double total = 0d;
        int current = start;
        for (int next : order) {
            Double edge = matrix[current][next];
            if (edge == null) {
                return Double.POSITIVE_INFINITY;
            }
            total += edge;
            current = next;
        }
        return total;
    }

    private static final class Best {
        private double cost = Double.POSITIVE_INFINITY;
        private List<Integer> order;
    }
}
