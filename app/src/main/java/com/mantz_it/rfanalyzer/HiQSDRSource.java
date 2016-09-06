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
    protected static final int MAX_SAMPLE_RATE = 1920000;
    //protected static final int[] SAMPLE_RATES = {48000, 96000, 192000, 384000};
    protected static final int[] SAMPLE_RATES = {
            1920000,
            960000,
            480000,
            320000,
            240000,
            192000,
            160000,
            120000,
            96000,
            80000,
            64000,
            60000,
            48000
    };
    protected static final int[] SAMPLE_RATE_CODES = {
            0x1,
            0x2,
            0x4,
            0x6,
            0x8,
            0xA,
            0xC,
            0xF,
            0x14,
            0x18,
            0x1E,
            0x20,
            0x28
    };
    protected static final int RX_PACKET_SIZE = 1442;
    // TODO: determine exact frequency limits
    protected static final long MIN_FREQUENCY = 100000L; // 100 kHz
    protected static final long CLOCK_RATE = 122880000L;
    protected static final long MAX_FREQUENCY = CLOCK_RATE / 2; // (?)61 MHz, but there is info, that 66 MHz
    //---------------------------------------
    protected static final int TX_CTRL_ENABLE_CW_TRANSMIT = 0x1;
    protected static final int TX_CTRL_ENABLE_OTHER_TRANSMIT = 0x2;
    protected static final int TX_CTRL_USE_EXTENDED_PINS = 0x4;
    protected static final int TX_CTRL_KEY_DOWN = 0x8;

    //---------------------------------------
    protected final byte[] cmdPacket = new byte[22];
    protected int txPowerLevel;
    protected int txControl;
    protected int rxControl;
    protected int firmwareVersion;
    protected int preselector;
    protected int attenuator;
    protected String ipAddress;
    protected int cmdPort;
    protected int rxPort;
    protected int txPort;
    //---------------------------------------
    protected Callback callback;
    protected CommandThread commandThread;
    protected ReceivingThread receiverThread;
    protected IQConverter iqConverter;
    protected int sampleRate;
    protected long rxTuneFrequency;
    protected long rxTunePhase;
    protected long txTunePhase; // for future

    public HiQSDRSource(String ip, int cmdPort, int rxPort, int txPort) {

    }

    protected long frequencyToTunePhase(long frequency) {
        return (long) (((frequency / CLOCK_RATE) * (1L << 32)) + 0.5);
    }

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
        return rxTuneFrequency;
    }

    @Override
    public void setFrequency(long frequency) {
        rxTuneFrequency = frequency;
        rxTunePhase = frequencyToTunePhase(rxTuneFrequency);
        // TODO: send control packet after this
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
        for (int i = SAMPLE_RATES.length - 1; i >= 0; --i)
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
        return RX_PACKET_SIZE;
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
            super("HiQSDR CommandThread");
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
