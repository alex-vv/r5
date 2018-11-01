package com.conveyal.r5.streets;

public interface TravelCostCalculator {
    float getGeneralizedTravelCost(EdgeStore.Edge edge, int legDurationSeconds, float traversalTimeMilliseconds);
}
