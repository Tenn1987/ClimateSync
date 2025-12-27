package com.brandon.climatesync.service;

import com.brandon.climatesync.model.ClimateState;
import com.brandon.climatesync.provider.WeatherProvider;

public class ClimateClassifier {

    private final double coldMaxC;
    private final double hotMinC;

    public ClimateClassifier(double coldMaxC, double hotMinC) {
        this.coldMaxC = coldMaxC;
        this.hotMinC = hotMinC;
    }

    public ClimateState classify(WeatherProvider.Observed obs) {
        double t = obs.tempC();
        boolean wet = obs.wet();

        if (t <= coldMaxC) return wet ? ClimateState.COLD_WET : ClimateState.COLD_DRY;

        if (t >= hotMinC) return wet ? ClimateState.HOT_WET : ClimateState.HOT_DRY;

        return wet ? ClimateState.WARM_WET : ClimateState.WARM_DRY;
    }
}
