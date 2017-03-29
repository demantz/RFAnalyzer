package com.mantz_it.rfanalyzer.model;


import com.mantz_it.rfanalyzer.control.Controllable;

/**
 * Interface for controlling frequency tuning. This interface should not be used as parameter
 * for {@link Controllable#getControl(Class)} method, because it's only abstraction over frequency control,
 * and entity can have multiple controllable frequencies (TX/RX, for example)
 * <p>
 * Created by Pavel on 20.03.2017.
 */

public interface Frequency extends ConstrainedProperty<Long> {
int getFrequencyShift();

void setFrequencyShift(int frequencyShift);
}
