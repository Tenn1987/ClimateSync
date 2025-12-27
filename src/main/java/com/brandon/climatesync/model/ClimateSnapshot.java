package com.brandon.climatesync.model;

public record ClimateSnapshot(
        String zoneId,
        ClimateState state,
        boolean thunder,
        boolean wet,
        double tempC,
        String observedMain,

        // new fields
        double rainMm,
        double snowMm,
        int windowHours,

        long updatedEpochSeconds
) {}
