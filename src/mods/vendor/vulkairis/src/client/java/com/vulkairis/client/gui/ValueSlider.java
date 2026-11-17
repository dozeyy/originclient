package com.vulkairis.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/**
 * Numeric slider with a displayed value ({@code label: value}). Vanilla slider behavior
 * (drag, arrow keys) with Minecraft styling inherited from the vanilla slider button.
 */
public class ValueSlider extends AbstractSliderButton {

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer onChange;

    public ValueSlider(int x, int y, int width, int height, String label,
                       double min, double max, double step, double initial,
                       DoubleConsumer onChange) {
        super(x, y, width, height, Component.empty(), snap(initial, min, max, step));
        this.label = label != null ? label : "";
        this.min = min;
        this.max = max;
        this.step = step > 0.0 ? step : 0.0;
        this.onChange = onChange;
        updateMessage();
    }

    /** @return the current value in [min, max] */
    public double value() {
        return min + this.value * (max - min);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label + ": " + format(value())));
    }

    @Override
    protected void applyValue() {
        if (step > 0.0) {
            double stepped = min + Math.round((value() - min) / step) * step;
            this.value = (stepped - min) / (max - min);
            updateMessage();
        }
        if (onChange != null) {
            onChange.accept(value());
        }
    }

    private String format(double value) {
        if (step >= 1.0 || step == 0.0) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%." + decimals(step) + "f", value);
    }

    private static int decimals(double step) {
        int places = 0;
        double remainder = step;
        while (places < 6 && Math.abs(remainder - Math.round(remainder)) > 1e-9) {
            remainder *= 10.0;
            places++;
        }
        return Math.max(1, places);
    }

    private static double snap(double initial, double min, double max, double step) {
        double clamped = Math.min(max, Math.max(min, initial));
        if (step > 0.0 && max > min) {
            clamped = min + Math.round((clamped - min) / step) * step;
            return (clamped - min) / (max - min);
        }
        return max > min ? (clamped - min) / (max - min) : 0.0;
    }
}
