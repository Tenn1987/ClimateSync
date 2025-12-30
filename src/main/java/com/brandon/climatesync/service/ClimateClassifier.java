package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateState;

public final class ClimateClassifier {

    private final double coldMaxC;
    private final double hotMinC;

    public ClimateClassifier(double coldMaxC, double hotMinC) {
        this.coldMaxC = coldMaxC;
        this.hotMinC = hotMinC;
    }

    public ClimateState classify(double tempC, boolean wet, boolean thunder) {
        if (tempC <= coldMaxC) {
            return wet ? ClimateState.COLD_WET : ClimateState.COLD_DRY;
        }
        if (tempC >= hotMinC) {
            return wet ? ClimateState.HOT_WET : ClimateState.HOT_DRY;
        }
        return wet ? ClimateState.WARM_WET : ClimateState.WARM_DRY;
    }
}
