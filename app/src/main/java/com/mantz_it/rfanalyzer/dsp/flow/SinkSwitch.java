package com.mantz_it.rfanalyzer.dsp.flow;

/**
 * Created by pavlus on 14.01.17.
 */

public class SinkSwitch<T> extends Switch {
protected final Source<T> SOURCE;
protected final Sink<T> SINK;

public SinkSwitch(Source<T> source, Sink<T> sink) {
    this.SOURCE = source;
    this.SINK = sink;
}

@Override
public boolean enable() {
    return SOURCE.addSink(SINK);
}

@Override
public boolean disable() {
    return SOURCE.removeSink(SINK);
}
}
