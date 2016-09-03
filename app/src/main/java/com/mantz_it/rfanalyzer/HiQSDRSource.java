package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Created by pavlus on 10.06.16.
 */
public class HiQSDRSource implements IQSourceInterface {
private static final String LOGTAG = "HiQSDRSource";
private static final String NAME = "HiQSDRSource";
protected static final int MIN_SAMPLE_RATE = 48000;
protected static final int MAX_SAMPLE_RATE = 384000;
protected static final int[] SAMPLE_RATES = {48000, 96000, 192000, 384000};
// TODO: determine exact frequency limits
protected static int MIN_FREQUENCY = 1;
protected static int MAX_FREQUENCY = 122880000;
//---------------------------------------
protected Callback callback;
protected CommandThread commandThread;
protected ReceivingThread receiverThread;
protected IQConverter iqConverter;
protected int sampleRate;

@Override
public boolean open(Context context, Callback callback) {
    // TODO: init threads, let them do their job
    return false;
}

@Override
public boolean isOpen() {
    // TODO: should be determined by working threads
    return false;
}

@Override
public boolean close() {
    // TODO: stop working threads, reset variables
    return false;
}

@Override
public String getName() {
    return NAME;
}

@Override
public int getSampleRate() {
    return sampleRate;
}

@Override
public void setSampleRate(int sampleRate) {
    // TODO: implement in command thread
}

@Override
public long getFrequency() {
    // TODO: determine how to handle frequencies
    return 0;
}

@Override
public void setFrequency(long frequency) {
// TODO: determine how to calculate frequency by protocol
}

@Override
public long getMaxFrequency() {
    return MAX_FREQUENCY;
}

@Override
public long getMinFrequency() {
    return MIN_FREQUENCY;
}

@Override
public int getMaxSampleRate() {
    return MAX_SAMPLE_RATE;
}

@Override
public int getMinSampleRate() {
    return MIN_SAMPLE_RATE;
}

@Override
public int getNextHigherOptimalSampleRate(int sampleRate) {
    for (int i = 0; i < SAMPLE_RATES.length; ++i)
        if (sampleRate < SAMPLE_RATES[i])
            return SAMPLE_RATES[i]; // return first met higher
    return SAMPLE_RATES[SAMPLE_RATES.length - 1]; // if not found - return highest one
}

@Override
public int getNextLowerOptimalSampleRate(int sampleRate) {
    for (int i = SAMPLE_RATES.length-1; i >=0; --i)
        if (sampleRate > SAMPLE_RATES[i])
            return SAMPLE_RATES[i]; // return first met lower
    return SAMPLE_RATES[0]; // if not found - return lowest one

}

@Override
public int[] getSupportedSampleRates() {
    return SAMPLE_RATES;
}

@Override
public int getPacketSize() {
    // TODO: 25.06.16
    return 0;
}

@Override
public byte[] getPacket(int timeout) {
    // TODO: 25.06.16
    return new byte[0];
}

@Override
public void returnPacket(byte[] buffer) {
    // TODO: 25.06.16
}

@Override
public void startSampling() {
    if (receiverThread != null) {
        Log.e(LOGTAG, "startSampling: receiver thread still running.");
        reportError("Could not start sampling");
        return;
    }
    if (isOpen()) {
        // start ReceiverThread:
        //receiverThread = new ReceiverThread(inputStream, returnQueue, queue); TODO: 25.06.16 implement
        receiverThread.start();
    }
}

@Override
public void stopSampling() {
    // stop and join receiver thread:
    if (receiverThread != null) {
        receiverThread.stopReceiving();
        // Join the thread only if the current thread is NOT the receiverThread ^^
        if (!Thread.currentThread().getName().equals(receiverThread.threadName)) {
            try {
                receiverThread.join();
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "stopSampling: Interrupted while joining receiver thread: " + e.getMessage());
            }
        }
        receiverThread = null;
    }
}

@Override
public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
    return this.iqConverter.fillPacketIntoSamplePacket(packet, samplePacket);
}

@Override
public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
    return this.iqConverter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency);
}

/**
 * Will forward an error message to the callback object
 *
 * @param msg error message
 */
private void reportError(String msg) {
    if (callback != null)
        callback.onIQSourceError(this, msg);
    else
        Log.e(LOGTAG, "reportError: Callback is null. (Error: " + msg + ")");
}

protected class CommandThread extends Thread {
    protected DatagramSocket socket;
    protected InetAddress remoteAddr;
    protected int remotePort;

    public CommandThread(InetAddress addr, int port) {
        this.remoteAddr = addr;
        this.remotePort = port;
        socket.connect(remoteAddr, remotePort);
    }
}

protected class ReceivingThread extends Thread {
    protected DatagramSocket socket;
    protected InetAddress remoteAddr;
    protected String threadName;

    public void stopReceiving() {
        // TODO: 25.06.16 implement me
    }
}
}
