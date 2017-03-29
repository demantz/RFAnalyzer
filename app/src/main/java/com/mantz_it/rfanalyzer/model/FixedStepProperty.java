package com.mantz_it.rfanalyzer.model;

/**
 * Created by Pavel on 21.03.2017.
 */

public interface FixedStepProperty<T extends Number> extends ConstrainedProperty<T> {
T getStep();

T setByIndex(int index);

int getIndex();
}
