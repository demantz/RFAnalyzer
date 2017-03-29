package com.mantz_it.rfanalyzer.model;

import com.mantz_it.rfanalyzer.control.Control;

/**
 * Created by Pavel on 21.03.2017.
 */

public interface Property<T> {
void set(T value);

T get();
}
