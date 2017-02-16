package com.mantz_it.rfanalyzer.dsp.spi;

/**
 * Created by Pavel on 21.12.2016.
 */

public abstract class Demodulation implements Transformation<Packet> {
protected float gain = 1;

public abstract void setGain(float gain);

}
