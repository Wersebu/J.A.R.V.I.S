package com.jarvis.tools.dataset;

import com.jarvis.tools.location.GeoPoint;
import com.jarvis.tools.location.GeocodeResult;
import com.jarvis.tools.location.GeocodingClient;
import com.jarvis.tools.location.GoogleMapsLinks;
import com.jarvis.tools.location.RouteMatrixResult;
import com.jarvis.tools.location.RouteOptimizer;
import com.jarvis.tools.location.OptimizedRoute;
import com.jarvis.tools.location.RoutingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the Store Audit day plan in Core, following Work/Scheduling/StoreAuditScheduleWorkflow.md, instead of
 * leaving the geographic grouping to the model:
 *
 * <ul>
 *   <li>each day is a closed trip from the fixed start point (Nowa Wola by default);</li>
 *   <li>a Biedronka takes a quarter of a day, a short audit (Żabka, Stokrotka...) a seventh (the documented limits
 *       4 and 7 per day), with the documented visit times;</li>
 *   <li>the farthest region is planned first and filled with its nearest stores, so a distant region is not
 *       visited twice when it fits one trip;</li>
 *   <li>when a nearby leftover would force a second long trip but only slightly exceeds the limit, the plan does not
 *       decide alone: it records a decision (A merge / B keep separate) for the user;</li>
 *   <li>dates come from the user's preferences (preferred days first, fallback days only when needed, spread per
 *       strategy) on the real calendar.</li>
 * </ul>
 *
 * The plan stays a proposal the user can change (move a store, merge or split days, change a date, more or less work
 * per day) until accepted; the accepted plan is kept for a day so it can be exported to Google Tasks later.
 */
@Service
public class AuditPlanner {

    private static final Locale POLISH = Locale.forLanguageTag("pl");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", POLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", POLISH);
    private static final double EPS = 1e-9;
    /** One extra Biedronka (or one or two short audits) over a full day is "slightly over the limit". */
    private static final double BORDERLINE_OVERFLOW = 0.26;
    private static final Duration ACCEPTED_TTL = Duration.ofHours(24);

    /** Stores per day before a day counts as full. */
    public record PlannerLimits(int biedronkaPerDay, int shortPerDay) {
    }

    /** One visit in a day, in visit order. */
    public record PlannedStop(String recordId, int recordIndex, String network, String address, String city, int auditMinutes) {
    }

    /** One planned work day. */
    public record PlannedDay(int day, LocalDate date, boolean fallbackDay, boolean manualDate, List<PlannedStop> stops,
                             double distanceMeters, double travelSeconds, double auditSeconds, String region, List<String> mapsLinks) {
        public double workSeconds() {
            return travelSeconds + auditSeconds;
        }
    }

    /** A borderline case the workflow says to ask about: merging a nearby leftover into a day over its limit. */
    public record PlanDecision(int id, int day, List<String> recordIds, String text) {
    }

    /** The whole proposal (or the accepted plan). */
    public record AuditPlan(String datasetId, String conversationId, String startAddress, YearMonth month, PlannerLimits limits,
                            List<PlannedDay> days, List<PlanDecision> decisions, List<String> warnings, boolean accepted, Instant updatedAt) {
    }

    /** One change requested by the user; unset fields are ignored. */
    public record Adjustment(Integer moveStoreIndex, Integer toDay, List<Integer> mergeDays, Integer splitDay, Integer dateDay,
                             LocalDate date, Integer acceptDecision, Integer dismissDecision, Integer biedronkaPerDay, Integer shortPerDay) {
    }

    /** Geography of one dataset, reused for every change: index 0 is the start, store i is index i + 1. */
    private record Working(StoreAuditDataset dataset, GeoPoint start, String startAddress, Double[][] distances,
                           Double[][] durations, AuditPlan plan) {
    }

    private final GeocodingClient geocoder;
    private final RoutingClient routing;
    private final RouteOptimizer optimizer = new RouteOptimizer();
    private final Clock clock;
    private final String startAddress;
    private final int biedronkaMinutes;
    private final int shortMinutes;
    private final PlannerLimits defaultLimits;
    private final double regionSeconds;
    private final double farSeconds;
    private final double joinSeconds;
    private final Map<String, Working> working = new ConcurrentHashMap<>();
    private final Map<String, AuditPlan> acceptedByConversation = new ConcurrentHashMap<>();
    private final Map<String, GeoPoint> geocodedStarts = new ConcurrentHashMap<>();

    @Autowired
    public AuditPlanner(GeocodingClient geocoder, RoutingClient routing,
                        @Value("${jarvis.audit.start-address:Nowa Wola, 05-500, Polska}") String startAddress,
                        @Value("${jarvis.audit.biedronka-minutes:105}") int biedronkaMinutes,
                        @Value("${jarvis.audit.short-minutes:8}") int shortMinutes,
                        @Value("${jarvis.audit.biedronka-per-day:4}") int biedronkaPerDay,
                        @Value("${jarvis.audit.short-per-day:7}") int shortPerDay,
                        @Value("${jarvis.audit.region-minutes:15}") int regionMinutes,
                        @Value("${jarvis.audit.far-minutes:35}") int farMinutes,
                        @Value("${jarvis.audit.join-minutes:40}") int joinMinutes) {
        this(geocoder, routing, Clock.systemDefaultZone(), startAddress, biedronkaMinutes, shortMinutes,
                new PlannerLimits(biedronkaPerDay, shortPerDay), regionMinutes, farMinutes, joinMinutes);
    }

    /** With an explicit clock and rules (tests, also from other modules). */
    public AuditPlanner(GeocodingClient geocoder, RoutingClient routing, Clock clock, String startAddress, int biedronkaMinutes,
                 int shortMinutes, PlannerLimits limits, int regionMinutes, int farMinutes, int joinMinutes) {
        this.geocoder = geocoder;
        this.routing = routing;
        this.clock = clock;
        this.startAddress = startAddress;
        this.biedronkaMinutes = biedronkaMinutes;
        this.shortMinutes = shortMinutes;
        this.defaultLimits = limits;
        this.regionSeconds = regionMinutes * 60.0;
        this.farSeconds = farMinutes * 60.0;
        this.joinSeconds = joinMinutes * 60.0;
    }

    // ---- public API ----

    /** A new proposal for a geocoded dataset with preferences. */
    public AuditPlan plan(StoreAuditDataset dataset, PlannerLimits limitsOverride) {
        if (dataset.preferences() == null) {
            throw new IllegalStateException("Scheduling preferences are not set yet: call storeDataset.SET_PREFERENCES first.");
        }
        List<StoreRecord> stores = dataset.stores();
        List<String> missing = stores.stream().filter(s -> s.latitude() == null || s.longitude() == null)
                .map(s -> "#" + (stores.indexOf(s) + 1) + " " + s.fullAddress()).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("These stores have no location yet: " + String.join("; ", missing)
                    + ". Geocode them (location.GEOCODE_DATASET) before planning.");
        }
        GeoPoint start = geocodedStarts.computeIfAbsent(startAddress, this::geocodeStart);
        List<GeoPoint> points = new ArrayList<>();
        points.add(start);
        for (StoreRecord store : stores) points.add(new GeoPoint(store.latitude(), store.longitude(), store.fullAddress()));
        RouteMatrixResult matrix = routing.table(points);
        if (!matrix.resolved()) {
            throw new IllegalStateException("Driving times could not be computed (" + matrix.failureReason() + "). Try again later.");
        }
        PlannerLimits limits = limitsOverride == null ? defaultLimits : limitsOverride;
        Working base = new Working(dataset, start, startAddress, matrix.distancesMeters(), matrix.durationsSeconds(), null);
        List<String> warnings = new ArrayList<>();
        List<PlanDecision> decisions = new ArrayList<>();
        List<List<Integer>> groups = group(base, limits, decisions);
        AuditPlan plan = build(base, limits, groups, Map.of(), decisions, warnings);
        working.put(dataset.datasetId(), new Working(dataset, start, startAddress, base.distances(), base.durations(), plan));
        return plan;
    }

    public Optional<AuditPlan> current(String datasetId) {
        return Optional.ofNullable(working.get(datasetId)).map(Working::plan);
    }

    /** Applies the user's change and re-routes; the plan stays a proposal. */
    public AuditPlan adjust(String datasetId, Adjustment change) {
        Working state = working.get(datasetId);
        if (state == null) throw new IllegalStateException("There is no plan for this dataset yet: call storeDataset.PLAN_SCHEDULE first.");
        if (change.biedronkaPerDay() != null || change.shortPerDay() != null) {
            PlannerLimits limits = new PlannerLimits(
                    change.biedronkaPerDay() != null ? range(change.biedronkaPerDay(), 1, 10, "biedronkaPerDay") : state.plan().limits().biedronkaPerDay(),
                    change.shortPerDay() != null ? range(change.shortPerDay(), 1, 20, "shortPerDay") : state.plan().limits().shortPerDay());
            List<PlanDecision> decisions = new ArrayList<>();
            List<List<Integer>> groups = group(state, limits, decisions);
            AuditPlan plan = build(state, limits, groups, Map.of(), decisions, new ArrayList<>());
            working.put(datasetId, withPlan(state, plan));
            return plan;
        }
        AuditPlan plan = state.plan();
        List<List<Integer>> groups = new ArrayList<>();
        Map<Integer, LocalDate> manual = new HashMap<>();
        for (PlannedDay day : plan.days()) {
            List<Integer> indexes = new ArrayList<>();
            for (PlannedStop stop : day.stops()) indexes.add(stop.recordIndex() - 1);
            if (day.manualDate()) manual.put(groups.size(), day.date());
            groups.add(indexes);
        }
        List<PlanDecision> decisions = new ArrayList<>(plan.decisions());
        if (change.moveStoreIndex() != null) {
            int store = range(change.moveStoreIndex(), 1, state.dataset().stores().size(), "storeIndex") - 1;
            int target = range(change.toDay() == null ? -1 : change.toDay(), 1, groups.size() + 1, "toDay") - 1;
            groups.forEach(g -> g.remove(Integer.valueOf(store)));
            if (target == groups.size()) groups.add(new ArrayList<>());
            groups.get(target).add(store);
            decisions.removeIf(d -> d.recordIds().contains(state.dataset().stores().get(store).id()));
        } else if (change.mergeDays() != null && change.mergeDays().size() >= 2) {
            List<Integer> days = change.mergeDays().stream().map(d -> range(d, 1, groups.size(), "mergeDays") - 1).distinct().sorted().toList();
            for (int i = days.size() - 1; i >= 1; i--) groups.get(days.getFirst()).addAll(groups.get(days.get(i)));
            for (int i = days.size() - 1; i >= 1; i--) groups.get(days.get(i)).clear();
        } else if (change.splitDay() != null) {
            int day = range(change.splitDay(), 1, groups.size(), "splitDay") - 1;
            List<Integer> stores = groups.get(day);
            if (stores.size() < 2) throw new IllegalArgumentException("Day " + (day + 1) + " has only one store; nothing to split.");
            List<List<Integer>> halves = splitGeographically(state, stores);
            groups.set(day, halves.get(0));
            groups.add(day + 1, halves.get(1));
            Map<Integer, LocalDate> shifted = new HashMap<>();
            manual.forEach((k, v) -> shifted.put(k > day ? k + 1 : k, v));
            manual = shifted;
        } else if (change.dateDay() != null && change.date() != null) {
            int day = range(change.dateDay(), 1, groups.size(), "day") - 1;
            checkDate(state.dataset().preferences(), change.date());
            manual.put(day, change.date());
        } else if (change.acceptDecision() != null) {
            PlanDecision decision = decisions.stream().filter(d -> d.id() == change.acceptDecision()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No open decision " + change.acceptDecision() + "."));
            Set<Integer> moved = new LinkedHashSet<>();
            for (String id : decision.recordIds()) moved.add(indexOf(state.dataset(), id));
            groups.forEach(g -> g.removeAll(moved));
            groups.get(decision.day() - 1).addAll(moved);
            decisions.remove(decision);
        } else if (change.dismissDecision() != null) {
            if (!decisions.removeIf(d -> d.id() == change.dismissDecision())) {
                throw new IllegalArgumentException("No open decision " + change.dismissDecision() + ".");
            }
        } else {
            throw new IllegalArgumentException("Say what to change: moveStoreIndex+toDay, mergeDays, splitDay, day+date, "
                    + "acceptDecision, dismissDecision, or biedronkaPerDay/shortPerDay.");
        }
        // Empty days disappear; manual dates follow their days.
        List<List<Integer>> kept = new ArrayList<>();
        Map<Integer, LocalDate> keptManual = new HashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).isEmpty()) continue;
            if (manual.containsKey(i)) keptManual.put(kept.size(), manual.get(i));
            kept.add(groups.get(i));
        }
        AuditPlan updated = build(state, plan.limits(), kept, keptManual, decisions, new ArrayList<>());
        working.put(datasetId, withPlan(state, updated));
        return updated;
    }

    /** The plan as the schedule SUBMIT_SCHEDULE validates (visit order, real dates, closed-route totals). */
    public List<ScheduleDay> toSchedule(AuditPlan plan) {
        List<ScheduleDay> days = new ArrayList<>();
        for (PlannedDay day : plan.days()) {
            days.add(new ScheduleDay(day.day(), day.date(), day.stops().stream().map(PlannedStop::recordId).toList(),
                    day.distanceMeters(), day.travelSeconds(), day.auditSeconds()));
        }
        return days;
    }

    /** Remembers the accepted plan of a conversation for a day (the dataset itself expires after two hours). */
    public AuditPlan markAccepted(String datasetId, String conversationId) {
        Working state = working.get(datasetId);
        if (state == null) throw new IllegalStateException("No plan to accept.");
        AuditPlan p = state.plan();
        String conversation = conversationId == null || conversationId.isBlank() ? p.conversationId() : conversationId;
        AuditPlan accepted = new AuditPlan(p.datasetId(), conversation, p.startAddress(), p.month(), p.limits(), p.days(),
                List.of(), p.warnings(), true, clock.instant());
        working.put(datasetId, withPlan(state, accepted));
        acceptedByConversation.put(conversation, accepted);
        return accepted;
    }

    /** The accepted plan of a conversation, for exporting it. */
    public Optional<AuditPlan> accepted(String conversationId) {
        AuditPlan plan = acceptedByConversation.get(conversationId == null ? "" : conversationId);
        if (plan == null || plan.updatedAt().plus(ACCEPTED_TTL).isBefore(clock.instant())) return Optional.empty();
        return Optional.of(plan);
    }

    // ---- grouping ----

    private double load(StoreRecord store, PlannerLimits limits) {
        return isBiedronka(store) ? 1.0 / limits.biedronkaPerDay() : 1.0 / limits.shortPerDay();
    }

    private static boolean isBiedronka(StoreRecord store) {
        return String.valueOf(store.network()).toLowerCase(Locale.ROOT).contains("biedronk");
    }

    private int auditMinutes(StoreRecord store) {
        return isBiedronka(store) ? biedronkaMinutes : shortMinutes;
    }

    private static double cost(Double value) {
        return value == null ? Double.POSITIVE_INFINITY : value;
    }

    /** Driving time from a store to the closest store already in the day. */
    private double nearness(Working state, List<Integer> day, int candidate) {
        double best = Double.POSITIVE_INFINITY;
        for (int member : day) {
            best = Math.min(best, Math.min(cost(state.durations()[member + 1][candidate + 1]), cost(state.durations()[candidate + 1][member + 1])));
        }
        return best;
    }

    /**
     * Farthest region first: start a day with the farthest unplanned store, add the nearest stores while the day has
     * room, then look at what is left close by - a leftover that would need its own long trip becomes a decision.
     */
    private List<List<Integer>> group(Working state, PlannerLimits limits, List<PlanDecision> decisions) {
        List<StoreRecord> stores = state.dataset().stores();
        Set<Integer> unplanned = new LinkedHashSet<>();
        for (int i = 0; i < stores.size(); i++) unplanned.add(i);
        List<List<Integer>> groups = new ArrayList<>();
        while (!unplanned.isEmpty()) {
            int seed = unplanned.stream().max(Comparator.comparingDouble(s -> roundTrip(state, s))).orElseThrow();
            List<Integer> day = new ArrayList<>(List.of(seed));
            unplanned.remove(seed);
            double load = load(stores.get(seed), limits);
            while (true) {
                double current = load;
                // Only stores within a sensible drive of the day: filling a day with a store from another region would
                // just force a second trip there later (priority 1 of the workflow).
                Optional<Integer> next = unplanned.stream()
                        .filter(c -> current + load(stores.get(c), limits) <= 1 + EPS)
                        .filter(c -> nearness(state, day, c) <= joinSeconds)
                        .min(Comparator.comparingDouble(c -> nearness(state, day, c)));
                if (next.isEmpty()) break;
                day.add(next.get());
                unplanned.remove(next.get());
                load += load(stores.get(next.get()), limits);
            }
            List<Integer> leftover = unplanned.stream().filter(c -> nearness(state, day, c) <= regionSeconds).toList();
            if (!leftover.isEmpty()) {
                double extra = leftover.stream().mapToDouble(c -> load(stores.get(c), limits)).sum();
                int closest = leftover.stream().min(Comparator.comparingDouble(c -> cost(state.durations()[0][c + 1]))).orElseThrow();
                if (cost(state.durations()[0][closest + 1]) >= farSeconds && load + extra <= 1 + BORDERLINE_OVERFLOW + EPS) {
                    decisions.add(decision(state, decisions.size() + 1, groups.size() + 1, day, leftover, limits));
                }
            }
            groups.add(day);
        }
        return groups;
    }

    private double roundTrip(Working state, int store) {
        return cost(state.durations()[0][store + 1]) + cost(state.durations()[store + 1][0]);
    }

    private PlanDecision decision(Working state, int id, int dayNumber, List<Integer> day, List<Integer> leftover, PlannerLimits limits) {
        List<StoreRecord> stores = state.dataset().stores();
        String region = region(stores, day);
        long biedronki = java.util.stream.Stream.concat(day.stream(), leftover.stream()).filter(i -> isBiedronka(stores.get(i))).count();
        long shortOnes = day.size() + leftover.size() - biedronki;
        int closest = leftover.stream().min(Comparator.comparingDouble(c -> cost(state.durations()[0][c + 1]))).orElseThrow();
        double trip = cost(state.distances()[0][closest + 1]) + cost(state.distances()[closest + 1][0]);
        String names = String.join(", ", leftover.stream().map(i -> stores.get(i).network() + " " + stores.get(i).fullAddress()).toList());
        String text = "Region " + region + ": " + names + " " + (leftover.size() == 1 ? "leży" : "leżą") + " blisko dnia " + dayNumber
                + ", ale nie mieści się w limicie (" + limits.biedronkaPerDay() + " Biedronki / " + limits.shortPerDay()
                + " krótkich audytów dziennie). Osobno = dodatkowy wyjazd ok. " + Math.round(trip / 1000) + " km. "
                + "A) dołożyć do dnia " + dayNumber + " (razem " + (biedronki > 0 ? biedronki + " Biedronek" : "")
                + (biedronki > 0 && shortOnes > 0 ? " i " : "") + (shortOnes > 0 ? shortOnes + " krótkich" : "") + "), "
                + "B) zostawić osobno.";
        return new PlanDecision(id, dayNumber, leftover.stream().map(i -> stores.get(i).id()).toList(), text);
    }

    /** Two halves along the direction the stores spread most (north-south or east-west). */
    private List<List<Integer>> splitGeographically(Working state, List<Integer> stores) {
        List<StoreRecord> records = state.dataset().stores();
        double latSpread = stores.stream().mapToDouble(i -> records.get(i).latitude()).max().orElse(0)
                - stores.stream().mapToDouble(i -> records.get(i).latitude()).min().orElse(0);
        double lonSpread = stores.stream().mapToDouble(i -> records.get(i).longitude()).max().orElse(0)
                - stores.stream().mapToDouble(i -> records.get(i).longitude()).min().orElse(0);
        List<Integer> sorted = new ArrayList<>(stores);
        sorted.sort(Comparator.comparingDouble(i -> latSpread >= lonSpread ? records.get(i).latitude() : records.get(i).longitude()));
        int half = sorted.size() / 2;
        return List.of(new ArrayList<>(sorted.subList(0, half)), new ArrayList<>(sorted.subList(half, sorted.size())));
    }

    // ---- days, routes, dates ----

    private AuditPlan build(Working state, PlannerLimits limits, List<List<Integer>> groups, Map<Integer, LocalDate> manualDates,
                            List<PlanDecision> decisions, List<String> warnings) {
        StoreAuditDataset dataset = state.dataset();
        SchedulingPreferences preferences = dataset.preferences();
        Map<Integer, LocalDate> dates = new HashMap<>(manualDates);
        Set<Integer> fallback = new LinkedHashSet<>();
        assignDates(preferences, groups.size(), dates, fallback, warnings);
        List<PlannedDay> days = new ArrayList<>();
        // Days follow the calendar: day 1 is the earliest date.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) order.add(i);
        order.sort(Comparator.comparing(i -> dates.getOrDefault(i, LocalDate.MAX)));
        List<PlanDecision> renumbered = new ArrayList<>();
        for (int position = 0; position < order.size(); position++) {
            int group = order.get(position);
            days.add(route(state, position + 1, groups.get(group), dates.get(group), fallback.contains(group), manualDates.containsKey(group)));
        }
        for (PlanDecision decision : decisions) {
            int newDay = order.indexOf(decision.day() - 1) + 1;
            renumbered.add(new PlanDecision(decision.id(), newDay, decision.recordIds(),
                    decision.text().replace("dnia " + decision.day(), "dnia " + newDay)));
        }
        return new AuditPlan(dataset.datasetId(), dataset.conversationId(), state.startAddress(),
                YearMonth.of(preferences.year(), preferences.month()), limits, days, renumbered, warnings, false, clock.instant());
    }

    private PlannedDay route(Working state, int dayNumber, List<Integer> group, LocalDate date, boolean fallbackDay, boolean manual) {
        List<StoreRecord> stores = state.dataset().stores();
        int size = group.size() + 1;
        Double[][] sub = new Double[size][size];
        for (int a = 0; a < size; a++) {
            for (int b = 0; b < size; b++) {
                int from = a == 0 ? 0 : group.get(a - 1) + 1;
                int to = b == 0 ? 0 : group.get(b - 1) + 1;
                sub[a][b] = state.durations()[from][to];
            }
        }
        OptimizedRoute best = optimizer.optimize(sub, 0, 8, true);
        List<Integer> visit = new ArrayList<>();
        for (int position : best.visitOrder()) if (position != 0) visit.add(group.get(position - 1));
        for (int position : best.unreachableIndices()) visit.add(group.get(position - 1));
        double meters = 0, seconds = 0, audit = 0;
        int previous = 0;
        List<PlannedStop> planned = new ArrayList<>();
        for (int store : visit) {
            meters += cost(state.distances()[previous][store + 1]);
            seconds += cost(state.durations()[previous][store + 1]);
            previous = store + 1;
            StoreRecord record = stores.get(store);
            audit += auditMinutes(record) * 60.0;
            planned.add(new PlannedStop(record.id(), store + 1, record.network(), record.fullAddress(), record.city(), auditMinutes(record)));
        }
        meters += cost(state.distances()[previous][0]);
        seconds += cost(state.durations()[previous][0]);
        List<String> links = GoogleMapsLinks.route(state.startAddress(), planned.stream().map(PlannedStop::address).toList(), state.startAddress());
        return new PlannedDay(dayNumber, date, fallbackDay, manual, planned, Double.isInfinite(meters) ? -1 : meters,
                Double.isInfinite(seconds) ? -1 : seconds, audit, region(stores, group), links);
    }

    /** "Garwolin i okolice": the city most of the day's stores are in. */
    private static String region(List<StoreRecord> stores, List<Integer> group) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i : group) counts.merge(String.valueOf(stores.get(i).city()).strip(), 1, Integer::sum);
        String city = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        return counts.size() > 1 ? city + " i okolice" : city;
    }

    /** Real dates from the preferences: preferred days first, fallback days only when needed, spread per strategy. */
    private void assignDates(SchedulingPreferences preferences, int dayCount, Map<Integer, LocalDate> dates, Set<Integer> fallbackDays,
                             List<String> warnings) {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.of(preferences.year(), preferences.month());
        LocalDate from;
        LocalDate to;
        if (preferences.hasExplicitRange()) {
            from = preferences.explicitStartDate();
            to = preferences.explicitEndDate();
        } else {
            switch (preferences.strategy()) {
                case BEGINNING -> { from = month.atDay(1); to = month.atDay(Math.min(14, month.lengthOfMonth())); }
                case ENDING -> { from = month.atEndOfMonth().minusDays(13); to = month.atEndOfMonth(); }
                default -> { from = month.atDay(1); to = month.atEndOfMonth(); }
            }
        }
        if (from.isBefore(today)) from = today;
        Set<DayOfWeek> preferred = preferences.preferredDaysOfWeek().isEmpty()
                ? EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY) : preferences.preferredDaysOfWeek();
        Set<DayOfWeek> extra = EnumSet.noneOf(DayOfWeek.class);
        extra.addAll(preferences.fallbackDaysOfWeek());
        if (preferences.saturdayExplicitlyAllowed()) extra.add(DayOfWeek.SATURDAY);
        extra.removeAll(preferred);
        List<LocalDate> taken = new ArrayList<>(dates.values());
        List<LocalDate> first = new ArrayList<>();
        List<LocalDate> second = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (taken.contains(d)) continue;
            if (preferred.contains(d.getDayOfWeek())) first.add(d);
            else if (extra.contains(d.getDayOfWeek())) second.add(d);
        }
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < dayCount; i++) if (!dates.containsKey(i)) open.add(i);
        List<LocalDate> chosen = new ArrayList<>(pick(first, Math.min(open.size(), first.size()), preferences.strategy()));
        List<LocalDate> fromFallback = pick(second, Math.min(open.size() - chosen.size(), second.size()), preferences.strategy());
        chosen.addAll(fromFallback);
        Collections.sort(chosen);
        if (chosen.size() < open.size()) {
            warnings.add("Za mało pasujących dni w " + MONTH.format(month) + ": potrzeba " + dayCount + ", są " + (chosen.size() + dates.size())
                    + ". Zapytaj, czy dopuścić inne dni tygodnia, sobotę albo większe obciążenie dnia (biedronkaPerDay/shortPerDay).");
        }
        for (int i = 0; i < open.size() && i < chosen.size(); i++) {
            dates.put(open.get(i), chosen.get(i));
            if (fromFallback.contains(chosen.get(i))) fallbackDays.add(open.get(i));
        }
    }

    private static List<LocalDate> pick(List<LocalDate> candidates, int count, DistributionStrategy strategy) {
        if (count <= 0) return List.of();
        if (count >= candidates.size()) return candidates;
        return switch (strategy) {
            case BEGINNING -> candidates.subList(0, count);
            case ENDING -> candidates.subList(candidates.size() - count, candidates.size());
            default -> {
                List<LocalDate> spread = new ArrayList<>();
                for (int i = 0; i < count; i++) spread.add(candidates.get((int) Math.floor((i + 0.5) * candidates.size() / count)));
                yield spread;
            }
        };
    }

    private void checkDate(SchedulingPreferences preferences, LocalDate date) {
        if (date.isBefore(LocalDate.now(clock))) throw new IllegalArgumentException("The date " + date + " is in the past.");
        Set<DayOfWeek> allowed = preferences.allowedDaysOfWeek();
        if (!allowed.isEmpty() && !allowed.contains(date.getDayOfWeek())) {
            throw new IllegalArgumentException(DAY.format(date) + " is not one of the agreed days " + allowed
                    + ". Change the preferences (SET_PREFERENCES) first if the user wants this day.");
        }
    }

    private static int indexOf(StoreAuditDataset dataset, String recordId) {
        for (int i = 0; i < dataset.stores().size(); i++) if (dataset.stores().get(i).id().equals(recordId)) return i;
        throw new IllegalArgumentException("Unknown store " + recordId);
    }

    private static int range(int value, int min, int max, String name) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max + " (got " + value + ").");
        return value;
    }

    private static Working withPlan(Working state, AuditPlan plan) {
        return new Working(state.dataset(), state.start(), state.startAddress(), state.distances(), state.durations(), plan);
    }

    private GeoPoint geocodeStart(String address) {
        GeocodeResult result = geocoder.geocode(address);
        if (!result.resolved()) {
            throw new IllegalStateException("The start point " + address + " could not be located (" + result.failureReason() + ").");
        }
        return new GeoPoint(result.latitude(), result.longitude(), address);
    }

    // ---- presentation ----

    /** The plan for the model to present (section 13 of the workflow document) and for exporting. */
    public static Map<String, Object> describe(AuditPlan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", plan.accepted());
        data.put("month", MONTH.format(plan.month()));
        data.put("start", plan.startAddress());
        data.put("limitsPerDay", Map.of("biedronka", plan.limits().biedronkaPerDay(), "short", plan.limits().shortPerDay()));
        List<Map<String, Object>> days = new ArrayList<>();
        double km = 0, work = 0;
        int stores = 0;
        for (PlannedDay day : plan.days()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", day.day());
            item.put("date", day.date() == null ? "brak daty" : DAY.format(day.date()));
            if (day.date() != null) item.put("dateIso", day.date().toString());
            if (day.fallbackDay()) item.put("fallbackDay", true);
            item.put("region", day.region());
            List<Map<String, Object>> stops = new ArrayList<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < day.stops().size(); i++) {
                PlannedStop stop = day.stops().get(i);
                stops.add(Map.of("order", i + 1, "storeIndex", stop.recordIndex(), "network", stop.network(), "address", stop.address(),
                        "auditMinutes", stop.auditMinutes()));
                counts.merge(stop.network(), 1, Integer::sum);
            }
            item.put("stores", stops);
            item.put("counts", counts);
            item.put("auditTime", hours(day.auditSeconds()));
            item.put("travelTime", day.travelSeconds() < 0 ? "nieznany" : hours(day.travelSeconds()));
            item.put("distanceKm", day.distanceMeters() < 0 ? "nieznany" : Math.round(day.distanceMeters() / 1000));
            item.put("workTime", day.travelSeconds() < 0 ? "nieznany" : hours(day.workSeconds()));
            item.put("mapsLinks", day.mapsLinks());
            days.add(item);
            km += Math.max(0, day.distanceMeters()) / 1000;
            work += Math.max(0, day.workSeconds());
            stores += day.stops().size();
        }
        data.put("days", days);
        data.put("totals", Map.of("days", plan.days().size(), "stores", stores, "distanceKm", Math.round(km), "workTime", hours(work)));
        if (!plan.decisions().isEmpty()) {
            data.put("decisions", plan.decisions().stream().map(d -> Map.of("id", d.id(), "question", d.text())).toList());
        }
        if (!plan.warnings().isEmpty()) data.put("warnings", plan.warnings());
        return data;
    }

    static String hours(double seconds) {
        long minutes = Math.round(seconds / 60);
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }
}
