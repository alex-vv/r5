package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.WorkerPerformanceTimers;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleBoardSearch;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TripScheduleSearch;
import com.conveyal.r5.profile.entur.util.AvgTimer;

import java.util.Collection;
import java.util.Iterator;


/**
 * The algorithm used herein is described in
 * <p>
 * Conway, Matthew Wigginton, Andrew Byrd, and Marco van der Linden. “Evidence-Based Transit and Land Use Sketch Planning
 * Using Interactive Accessibility Methods on Combined Schedule and Headway-Based Networks.” Transportation Research
 * Record 2653 (2017). doi:10.3141/2653-06.
 * <p>
 * Delling, Daniel, Thomas Pajor, and Renato Werneck. “Round-Based Public Transit Routing,” January 1, 2012.
 * http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
 * <p>
 * This version do support the following features:
 * <ul>
 *     <li>Raptor (R)
 *     <li>Range Raptor (RR)
 *     <li>Multi-criteria pareto optimal Range Raptor (McRR)
 *     <li>Reverse search in combination with R and RR
 * </ul>
 * This version do NOT support the following features:
 * <ul>
 *     <li>Frequency routes, supported by the original code using Monte Carlo methods (generating randomized schedules)
 * </ul>
 * <p>
 * This class originated as a rewrite of Conveyals RAPTOR code: https://github.com/conveyal/r5.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
@SuppressWarnings("Duplicates")
public abstract class AbstractRangeRaptorWorker<T extends TripScheduleInfo, S extends WorkerState<T>> implements Worker<T> {

    /**
     * The RangeRaptor state - we delegate keeping track of state to the state object,
     * this allow the worker implementation to focus on the algorithm, while
     * the state keep track of the result.
     * <p/>
     * This also allow us to try out different strategies for storing the result in memory.
     * For a long time we had a state witch stored all data as int arrays in addition to the
     * current object-oriented approach. There were no performance differences(=> GC is not
     * the bottle neck), so we dropped the integer array implementation.
     */
    protected final S state;

    /**
     * The trip search context.
     */
    private final SearchContext<T> context;

    public AbstractRangeRaptorWorker(
            SearchContext<T> context,
            S state
    ) {
        this.context = context;
        this.state = state;
    }

    /**
     * For each iteration (minute), calculate the minimum travel time to each transit stop in seconds.
     *
     * @return a unique set of paths
     */
    @Override
    final public Collection<Path<T>> route() {
        timerRoute().time(() -> {
            timerSetup(() -> context.transit().init());

            // The main outer loop iterates backward over all minutes in the departure times window.
            final IntIterator it = context.calculator().rangeRaptorMinutes();
            while (it.hasNext()) {
                // Run the raptor search for this particular departure time
                timerRouteByMinute(() -> runRaptorForMinute(it.next()));
            }
        });
        return state.extractPaths();
    }

    protected RangeRaptorRequest<T> request() {
        return context.request();
    }

    protected TuningParameters tuningParameters() {
        return context.tuningParameters();
    }

    protected TransitDataProvider<T> transit() {
        return context.transit();
    }

    protected TransitCalculator calculator() {
        return context.calculator();
    }

    private WorkerPerformanceTimers timers() {
        return context.timers();
    }

    protected abstract void prepareTransitForRoundAndPattern(TripPatternInfo<T> pattern, TripScheduleSearch<T> tripSearch);

    protected abstract void performTransitForRoundAndPatternAtStop(int stopPositionInPattern);

    /**
     * Calculate the maximum number of rounds to perform.
     */
    protected static int nRounds(TuningParameters tuningParameters) {
        return tuningParameters.maxNumberOfTransfers() + 1;
    }

    /**
     * Iterate over given pattern and calculate transit for each stop.
     * <p/>
     * This is protected to allow reverse search to override and step backwards.
     */
    private void performTransitForRoundAndEachStopInPattern(final TripPatternInfo<T> pattern) {
        IntIterator it = calculator().patternStopIterator(pattern.numberOfStopsInPattern());
        while (it.hasNext()) {
            performTransitForRoundAndPatternAtStop(it.next());
        }
    }

    /**
     * Create a trip search using {@link TripScheduleBoardSearch}.
     * <p/>
     * This is protected to allow reverse search to override and create a alight search instead.
     */
    private TripScheduleSearch<T> createTripSearch(TripPatternInfo<T> pattern) {
        return calculator().createTripSearch(pattern, this::skipTripSchedule);
    }

    /**
     * Skip trips NOT running on the day of the search and skip frequency trips
     */
    private boolean skipTripSchedule(T trip) {
        return !transit().isTripScheduleInService(trip);
    }

    /**
     * Perform one minute of a RAPTOR search.
     *
     * @param departureTime When this search departs.
     */
    private void runRaptorForMinute(int departureTime) {
        iterationSetup(departureTime);

        // Run the scheduled search
        // round 0 is the street search
        // We are using the Range-RAPTOR extension described in Delling, Daniel, Thomas Pajor, and Renato Werneck.
        // “Round-Based Public Transit Routing,” January 1, 2012. http://research.microsoft.com/pubs/156567/raptor_alenex.pdf.
        // ergo, we re-use the arrival times found in searches that have already occurred that depart later, because
        // the arrival time given departure at time t is upper-bounded by the arrival time given departure at minute t + 1.

        while (state.isNewRoundAvailable()) {
            state.prepareForNextRound();

            // NB since we have transfer limiting not bothering to cut off search when there are no more transfers
            // as that will be rare and complicates the code
            timerByMinuteScheduleSearch().time(this::calculateTransitForRound);

            timerByMinuteTransfers().time(this::transfersForRound);
        }

        // This state is repeatedly modified as the outer loop progresses over departure minutes.
        // We have to be careful here, the next iteration will modify the state, so we need to make
        // protective copies of any information we want to retain.
        state.iterationComplete();
    }

    /**
     * Set the departure time in the scheduled search to the given departure time,
     * and prepare for the scheduled search at the next-earlier minute.
     * <p/>
     * This method is protected to allow reverce search to override it.
     */
    private void iterationSetup(int iterationDepartureTime) {
        state.setupIteration(iterationDepartureTime);

        // add initial stops
        for (TransferLeg it : request().accessLegs()) {
            state.setInitialTimeForIteration(it, iterationDepartureTime);
        }
    }

    /**
     * Perform a scheduled search
     */
    private void calculateTransitForRound() {
        IntIterator stops = state.stopsTouchedPreviousRound();
        Iterator<? extends TripPatternInfo<T>> patternIterator = transit().patternIterator(stops);

        while (patternIterator.hasNext()) {
            TripPatternInfo<T> pattern = patternIterator.next();
            TripScheduleSearch<T> tripSearch = createTripSearch(pattern);

            prepareTransitForRoundAndPattern(pattern, tripSearch);

            performTransitForRoundAndEachStopInPattern(pattern);
        }
        state.transitsForRoundComplete();
    }


    private void transfersForRound() {
        IntIterator it = state.stopsTouchedByTransitCurrentRound();

        while (it.hasNext()) {
            final int fromStop = it.next();
            // no need to consider loop transfers, since we don't mark patterns here any more
            // loop transfers are already included by virtue of those stops having been reached
            state.transferToStops(fromStop, transit().getTransfers(fromStop));
        }
        state.transfersForRoundComplete();
    }

    // Track time spent, measure performance
    // TODO TGR - Replace by performance tests
    private void timerSetup(Runnable setup) { timers().timerSetup(setup); }
    private AvgTimer timerRoute() { return timers().timerRoute(); }
    private void timerRouteByMinute(Runnable routeByMinute) { timers().timerRouteByMinute(routeByMinute); }
    private AvgTimer timerByMinuteScheduleSearch() { return timers().timerByMinuteScheduleSearch(); }
    private AvgTimer timerByMinuteTransfers() { return timers().timerByMinuteTransfers(); }
}
