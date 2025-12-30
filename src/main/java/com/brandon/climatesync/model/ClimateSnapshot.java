package com.brandon.climatesync.model;

public record ClimateSnapshot(
        String zoneId,
        ClimateState state,
        double tempC,
        boolean wet,
        boolean thunder,
        String observedMain,
        long updatedEpochSeconds,
        int windowHours,
        double rainMm,
        double snowMm
) { }
