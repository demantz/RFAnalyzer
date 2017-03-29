package com.mantz_it.rfanalyzer.model;

/**
 * Created by Pavel on 21.03.2017.
 */

public interface IndexedProperty<T> extends Property<T> {
T setByIndex(int index);

int getIndex();

T valueAt(int index);

int size();
}
