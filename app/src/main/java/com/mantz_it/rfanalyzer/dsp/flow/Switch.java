package com.mantz_it.rfanalyzer.dsp.flow;

/**
 * Created by pavlus on 14.01.17.
 */

public abstract class Switch {
public abstract boolean enable();

public abstract boolean disable();

public boolean set(boolean val) {
    if (val)
        return enable();
    else
        return disable();
}

}
