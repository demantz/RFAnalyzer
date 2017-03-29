package com.mantz_it.rfanalyzer.model;

/**
 * Created by Pavel on 21.03.2017.
 */

public interface ConstrainedProperty<T extends Number> extends Property<T> {
/**
 * @return the maximum frequency to which the {@link com.mantz_it.rfanalyzer.control.Controllable Controllable} can be tuned
 */
T getMax();

/**
 * @return the minimum frequency to which the {@link com.mantz_it.rfanalyzer.control.Controllable Controllable} can be tuned
 */
T getMin();
}
