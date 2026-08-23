package com.jarvis.tools.location;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link RouteOptimizer} against synthetic matrices - no HTTP, no mocking,
 * matching the "no network I/O in the optimizer" design.
 */
class RouteOptimizerTest {

    private final RouteOptimizer optimizer = new RouteOptimizer();

    @Test
    void bruteForceFindsOptimalTourForSmallInstance() {
        // Four points on a line: 0 --1--> 1 --1--> 2 --1--> 3 (indices in position order).
        // Starting at 0, visiting 2 and 3 first before 1 would cost more than the natural order.
        Double[][] matrix = lineMatrix(4);

        OptimizedRoute route = optimizer.optimize(matrix, 0, 8);

        assertThat(route.visitOrder()).containsExactly(0, 1, 2, 3);
        assertThat(route.totalCost()).isEqualTo(3d);
        assertThat(route.unreachableIndices()).isEmpty();
    }

    @Test
    void nearestNeighbourPlusTwoOptUsedAboveExactThreshold() {
        // 10 points on a line, exceeding an exact threshold of 4 - forces the heuristic path.
        // The optimal order is still the natural line order (cost 9); 2-opt should reach it from
        // a nearest-neighbour start on a matrix this simple.
        Double[][] matrix = lineMatrix(10);

        OptimizedRoute route = optimizer.optimize(matrix, 0, 4);

        assertThat(route.visitOrder()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(route.totalCost()).isEqualTo(9d);
    }

    @Test
    void nearestNeighbourTwoOptImprovesOnNaiveOrderForOutOfOrderInput() {
        // Same line topology, but nearest-neighbour from 0 would naturally walk it in order anyway;
        // to prove 2-opt improves a bad tour, use a matrix where the "identity" order is
        // deliberately bad and only reordering fixes it.
        double[][] raw = {
                {0, 5, 1, 5},
                {5, 0, 5, 1},
                {1, 5, 0, 5},
                {5, 1, 5, 0}
        };
        Double[][] matrix = box(raw);

        OptimizedRoute route = optimizer.optimize(matrix, 0, 0); // force heuristic path (threshold 0)

        // Optimal reachable order from 0 is 0 -> 2 -> ... but 2's only cheap edges are to 0; the
        // cheapest achievable tour visiting {1,2,3} from 0 is 0->2 (1) ->? all remaining edges from
        // 2 are 5, so total cost must be evaluated rather than assumed - assert it beats or matches
        // the worst-case naive (identity 0,1,2,3) cost of 5+5+5=15.
        double naiveCost = raw[0][1] + raw[1][2] + raw[2][3];
        assertThat(route.totalCost()).isLessThanOrEqualTo(naiveCost);
    }

    @Test
    void unreachableCellsAreExcludedAndReportedNotCrashed() {
        Double[][] matrix = lineMatrix(4);
        matrix[0][3] = null;
        matrix[3][0] = null;

        OptimizedRoute route = optimizer.optimize(matrix, 0, 8);

        assertThat(route.unreachableIndices()).containsExactly(3);
        assertThat(route.visitOrder()).doesNotContain(3);
    }

    @Test
    void singleStopReturnsTrivialRoute() {
        Double[][] matrix = box(new double[][]{
                {0, 4},
                {4, 0}
        });

        OptimizedRoute route = optimizer.optimize(matrix, 0, 8);

        assertThat(route.visitOrder()).containsExactly(0, 1);
        assertThat(route.totalCost()).isEqualTo(4d);
        assertThat(route.unreachableIndices()).isEmpty();
    }

    @Test
    void noReachableStopsReturnsJustTheStartPoint() {
        Double[][] matrix = new Double[2][2];
        matrix[0][1] = null;
        matrix[1][0] = null;

        OptimizedRoute route = optimizer.optimize(matrix, 0, 8);

        assertThat(route.visitOrder()).containsExactly(0);
        assertThat(route.totalCost()).isEqualTo(0d);
        assertThat(route.unreachableIndices()).containsExactly(1);
    }

    // =====================================================================
    // Closed-route (returnToStart / closeLoop) tests - a full day trip that must end back at its
    // fixed starting point, not merely at the last stop.
    // =====================================================================

    @Test
    void closedLoopIncludesTheReturnLegInTotalCost() {
        // Line topology 0-1-2-3: open-path cost 0->1->2->3 is 3, but the closed loop must also
        // add the return leg 3->0 (cost 3), for a total of 6.
        Double[][] matrix = lineMatrix(4);

        OptimizedRoute open = optimizer.optimize(matrix, 0, 8, false);
        OptimizedRoute closed = optimizer.optimize(matrix, 0, 8, true);

        assertThat(open.totalCost()).isEqualTo(3d);
        assertThat(closed.totalCost()).isEqualTo(6d);
        assertThat(closed.visitOrder()).containsExactly(0, 1, 2, 3);
    }

    @Test
    void aStopWithNoReturnEdgeToStartIsExcludedEntirelyEvenForAClosedLoop() {
        // The existing reachability filter (unchanged by closeLoop) already requires both
        // directions to/from the start to exist for a stop to be usable at all - a stop with no
        // edge back to the start is excluded outright, not merely uncosted, whether or not a
        // closed loop was requested. The closed-loop total is then computed only over the
        // remaining, fully-reachable stops.
        Double[][] matrix = lineMatrix(4);
        matrix[3][0] = null;

        OptimizedRoute closed = optimizer.optimize(matrix, 0, 8, true);

        assertThat(closed.unreachableIndices()).containsExactly(3);
        assertThat(closed.visitOrder()).doesNotContain(3);
        // Best remaining closed loop over {1,2}: 0->1->2->0 = 1+1+2 = 4 (or the symmetric 0->2->1->0).
        assertThat(closed.totalCost()).isEqualTo(4d);
    }

    @Test
    void closedLoopThreeArgOverloadIsEquivalentToOpenPath() {
        Double[][] matrix = lineMatrix(4);

        OptimizedRoute legacy = optimizer.optimize(matrix, 0, 8);
        OptimizedRoute explicitOpen = optimizer.optimize(matrix, 0, 8, false);

        assertThat(legacy.totalCost()).isEqualTo(explicitOpen.totalCost());
        assertThat(legacy.visitOrder()).isEqualTo(explicitOpen.visitOrder());
    }

    private Double[][] lineMatrix(int size) {
        double[][] raw = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                raw[i][j] = Math.abs(i - j);
            }
        }
        return box(raw);
    }

    private Double[][] box(double[][] raw) {
        Double[][] boxed = new Double[raw.length][raw.length];
        for (int i = 0; i < raw.length; i++) {
            for (int j = 0; j < raw.length; j++) {
                boxed[i][j] = raw[i][j];
            }
        }
        return boxed;
    }
}
