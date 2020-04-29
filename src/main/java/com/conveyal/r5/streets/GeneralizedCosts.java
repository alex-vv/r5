package com.conveyal.r5.streets;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.profile.StreetMode;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;

/**
 * This stores costs per edge (units similar to seconds). Currently these costs must be specified by special tags in the
 * OSM input data, which must be generated by the data provider using external scripts. This mechanism could eventually
 * be extended, with R5 precalculating its own edge weights (including turn costs) from standard OSM tags that are
 * used in a wider range of places.
 * 
 * Input data has forward/backward tags, because OSM ways represent both directions of a road. R5 edges are directed, so
 * all costs are "forward".
 */
public class GeneralizedCosts {

    // This GeneralizedCosts augments this EdgeStore with additional information about the same edges.
    private final EdgeStore edgeStore;

    // For the walk mode, we store the cost of traversing the edge,
    // as well as the cost of continuing through the intersection and turning left or right.
    TDoubleList walkLink = new TDoubleArrayList();
    TDoubleList walkStraight = new TDoubleArrayList();
    TDoubleList walkLeft = new TDoubleArrayList();
    TDoubleList walkRight = new TDoubleArrayList();

    // For the bike mode we store the same set of costs as for the walk mode
    TDoubleList bikeLink = new TDoubleArrayList();
    TDoubleList bikeStraight = new TDoubleArrayList();
    TDoubleList bikeLeft = new TDoubleArrayList();
    TDoubleList bikeRight = new TDoubleArrayList();

    // Speeds
    TDoubleList speedPeak = new TDoubleArrayList();
    TDoubleList speedOffPeak = new TDoubleArrayList();

    TDoubleList aadt = new TDoubleArrayList(); // What is this? It appears in the RSG README.

    public GeneralizedCosts (EdgeStore edgeStore) {
        this.edgeStore = edgeStore;
    }

    public int size() {
        // All lists should be the same length by design, so any one of them tells us how many edges are covered.
        return walkLink.size();
    }

    /**
     * Called during TransportNetwork building. Extend all the parallel lists by two elements, one for the forward and
     * one for the backward edge derived from a single intersection-to-intersection segment of an OSM Way. For the time
     * being, all edges derived from a single Way will have the same generalized costs, so this may be called several
     * times in a row with the same Way.
     */
    public void addFromWay (Way way) {

        // Forward edge, walking
        walkLink    .add(parseTag(way,"gen_cost_ped:forward:link"));
        walkStraight.add(parseTag(way,"gen_cost_ped:forward:straight"));
        walkLeft    .add(parseTag(way,"gen_cost_ped:forward:left"));
        walkRight   .add(parseTag(way,"gen_cost_ped:forward:right"));

        // Forward edge, walking
        bikeLink    .add(parseTag(way,"gen_cost_bike:forward:link"));
        bikeStraight.add(parseTag(way,"gen_cost_bike:forward:straight"));
        bikeLeft    .add(parseTag(way,"gen_cost_bike:forward:left"));
        bikeRight   .add(parseTag(way,"gen_cost_bike:forward:right"));

        // Forward edge, speeds
        speedPeak   .add(parseTag(way,"speed_peak:forward"));
        speedOffPeak.add(parseTag(way,"speed_offpeak:forward"));
        aadt        .add(parseTag(way,"aadt"));

        // Backward edge, walking
        walkLink    .add(parseTag(way,"gen_cost_ped:backward:link"));
        walkStraight.add(parseTag(way,"gen_cost_ped:backward:straight"));
        walkLeft    .add(parseTag(way,"gen_cost_ped:backward:left"));
        walkRight   .add(parseTag(way,"gen_cost_ped:backward:right"));

        // Backward edge, biking
        bikeLink    .add(parseTag(way,"gen_cost_bike:backward:link"));
        bikeStraight.add(parseTag(way,"gen_cost_bike:backward:straight"));
        bikeLeft    .add(parseTag(way,"gen_cost_bike:backward:left"));
        bikeRight   .add(parseTag(way,"gen_cost_bike:backward:right"));

        // Backward edge, speeds
        speedPeak   .add(parseTag(way,"speed_peak:backward"));
        speedOffPeak.add(parseTag(way,"speed_offpeak:backward"));
        aadt        .add(parseTag(way,"aadt"));

    }

    /**
     * Read a single tag from the given OSM way and interpret it as a double-precision floating point value.
     * The tag is expected to be present on the way and be numeric. An exception will be thrown if it is not.
     */
    private static double parseTag (Way way, String tagKey) {
        String tagValue = way.getTag(tagKey);
        if (tagValue == null) {
            throw new RuntimeException("All ways are expected to have generalized cost tags. Missing: " + tagKey);
        }
        // Our inputs contain nan rather than the NaN expected by Double.parseDouble.
        if ("NaN".equalsIgnoreCase(tagValue)) {
            return Double.NaN;
        }
        try {
            double generalizedCost = Double.parseDouble(tagValue);
            return generalizedCost;
        } catch (NumberFormatException nfe) {
            throw new RuntimeException("Could not parse generalized cost tag as a number: " + tagValue);
        }
    }

    /**
     * Note that this matches the implicit interface of TurnCostCalculator.
     * Perhaps GeneralizedCosts can be split out into a custom TurnCostCalculator and WeightCalculator.
     */
    public double getGeneralizedCost (int currentEdge, int previousEdge, StreetMode mode) {
        double linkCost;
        if (currentEdge < this.size()) {
            if (mode == StreetMode.WALK) {
                linkCost = walkLink.get(currentEdge);
            } else if (mode == StreetMode.BICYCLE) {
                linkCost = bikeLink.get(currentEdge);
            } else {
                throw new RuntimeException("Generalized cost not supported for mode: " + mode);
            }
        } else {
            // Return unweighted length for edges that not appearing in this table, currently including split edges.
            return edgeStore.getCursor(currentEdge).getLengthM();
        }

        double turnCost = 0;
        // Only find nonzero turn cost if there is a previous edge (we are not on the first edge transition)
        // and if the edge appears within this generalized cost table.
        if (previousEdge >= 0 && previousEdge < this.size()) {
            EdgeStore.Edge e = edgeStore.getCursor();
            e.seek(currentEdge);
            int inAngle = e.getInAngle();
            e.seek(previousEdge);
            int outAngle = e.getOutAngle();
            TurnDirection turnDirection = TurnDirection.betweenAnglesDegrees(inAngle, outAngle);
            if (mode == StreetMode.WALK) {
                switch (turnDirection) {
                    case STRAIGHT:
                        turnCost = walkStraight.get(previousEdge);
                        break;
                    case LEFT:
                        turnCost = walkLeft.get(previousEdge);
                        break;
                    case RIGHT:
                        turnCost = walkRight.get(previousEdge);
                        break;
                    default:
                        throw new RuntimeException("No costs are specified for u-turns.");
                }
            } else if (mode == StreetMode.BICYCLE) {
                switch (turnDirection) {
                    case STRAIGHT:
                        turnCost = bikeStraight.get(previousEdge);
                        break;
                    case LEFT:
                        turnCost = bikeLeft.get(previousEdge);
                        break;
                    case RIGHT:
                        turnCost = bikeRight.get(previousEdge);
                        break;
                    default:
                        throw new RuntimeException("No costs are specified for u-turns.");
                }
            } else {
                throw new RuntimeException("Generalized cost not supported for mode: " + mode);
            }
        }
        return linkCost + turnCost;
    }

    private enum TurnDirection {
        STRAIGHT, RIGHT, LEFT, UTURN;
        public static TurnDirection forAngleDegrees (double angleDegrees) {
            if (angleDegrees < 27) {
                return STRAIGHT;
            } else if (angleDegrees < 153) {
                return RIGHT;
            } else if (angleDegrees < 207) {
                return UTURN;
            } else if (angleDegrees < 333) {
                return LEFT;
            } else {
                return STRAIGHT;
            }
        }
        public static TurnDirection betweenAnglesDegrees (int inAngle, int outAngle) {
            if (inAngle < outAngle) {
                inAngle += 360;
            }
            int turnAngle = inAngle - outAngle;
            return TurnDirection.forAngleDegrees(turnAngle);
        }
    }

}