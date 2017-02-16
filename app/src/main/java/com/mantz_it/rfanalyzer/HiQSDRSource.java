package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by pavlus on 10.06.16.
 */
public class HiQSDRSource implements IQSourceInterface, Runnable {
    private static final String LOGTAG = "HiQSDRSource";
    private static final String NAME = "HiQSDRSource";

    protected static final int CLOCK_RATE = 122880000;
    protected static final int UDP_CLK_RATE = CLOCK_RATE / 64; // actually 64 is prescaler*8, and default prescaler is 8, TBD.

    protected static int MIN_SAMPLE_RATE = 48000;
    protected static int MAX_SAMPLE_RATE = 960000;
    protected static int[] SAMPLE_RATES = {
            960000,  // decimation 2
            640000,  // d3
            480000,  // d4
            384000,  // d5
            320000,  // d6
            240000,  // d8
            192000,  // d10
            120000,  // d16
            96000,   // d20
            60000,   // d32
            48000    // d40
    };
    protected static byte[] SAMPLE_RATE_CODES = {1, 2, 3, 4, 5, 7, 9, 15, 19, 31, 39}; // decimation-1

    // TODO: determine exact frequency limits
    protected static final int MIN_FREQUENCY = 100000; // 100 kHz
    protected static final int MAX_FREQUENCY = CLOCK_RATE / 2; // (?)61 MHz, but there is info, that 66 MHz

    protected static final int RX_PACKET_SIZE = 1442;
    protected static final int RX_PAYLOAD_OFFSET = 2;
    protected static final int RX_PAYLOAD_SIZE = 1440;
    protected static final int CFG_PACKET_SIZE = 22;
    protected static final int CMD_PACKET_SIZE = 2;
    //---------------------------------------
    // todo: check names and actual modes
    public static final byte TX_MODE_INVALID = 0x0;
    public static final byte TX_MODE_KEYED_CONTINIOUS_WAVE = 0x1;
    public static final byte TX_MODE_RECEIVED_PTT = 0x2;
    public static final byte TX_MODE_EXTENDED_IO = 0x4;
    public static final byte TX_MODE_HW_CONTNIOUS_WAVE = 0x8;
    // FSM
    private volatile int state;
    private static final int STATE_EXIT = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_UPDATE_CFG = 1;
    private static final int STATE_START_RECEIVING = 2;
    private static final int STATE_RECEIVING = 3;
    private static final int STATE_STOP_RECEIVING = 4;
    private static final int STATE_CLOSE = 5;
    // commands
    protected final static ByteBuffer START_RECEIVING_CMD = ByteBuffer.allocateDirect(2).put(new byte[]{'r', 'r'}).asReadOnlyBuffer();
    protected final static ByteBuffer STOP_RECEIVING_CMD = ByteBuffer.allocateDirect(2).put(new byte[]{'s', 's'}).asReadOnlyBuffer();

    //---------------------------------------
    protected Config config;
    protected String ipAddress;
    protected int cmdPort;
    protected int rxPort;
    protected int txPort;

    protected Object lock = new Object();
    protected Callback callback;
    protected Context context;
    protected IQConverter iqConverter;
    protected Thread workerThread;
    protected InetAddress remoteAddr;

    protected InetSocketAddress cfgAddr;
    protected DatagramChannel configChannel;
    protected Selector configSelector;

    protected InetSocketAddress rxAddr;
    protected Selector receiverSelector;
    protected DatagramChannel receiverChannel;

    private volatile boolean deviceResponded = false;
    private volatile boolean receiving = false;
    private volatile boolean closing = false;
    protected volatile byte previousPacketIdx = 0;

    protected static final int INTERMEDIATE_BUFFER_SCALE = 10; // 12+ will fragment buffer,
    protected static final int INTERMEDIATE_BUFFER_SIZE = RX_PAYLOAD_SIZE * INTERMEDIATE_BUFFER_SCALE;
    protected static final int INTERMEDIATE_POOL_SIZE = 256;

    protected ArrayBlockingQueue<ByteBuffer> intermediateBufferQueue = new ArrayBlockingQueue<>(INTERMEDIATE_POOL_SIZE);
    protected ArrayBlockingQueue<ByteBuffer> intermediateBufferBusyQueue = new ArrayBlockingQueue<>(INTERMEDIATE_POOL_SIZE);
    protected ArrayBlockingQueue<ByteBuffer> intermediateBufferPool = new ArrayBlockingQueue<>(INTERMEDIATE_POOL_SIZE);
    protected ByteBuffer intermediateBuffer;
    protected byte[] stubBuffer;
    protected ByteBuffer headerBuffer = ByteBuffer.allocateDirect(RX_PAYLOAD_OFFSET);
    protected final ByteBuffer[] scatteringBuffer = {headerBuffer, intermediateBuffer};
    protected HashMap<byte[], ByteBuffer> arrayToBufferMap = new HashMap<>(INTERMEDIATE_POOL_SIZE);
    protected HashMap<ByteBuffer, byte[]> bufferToArrayMap = new HashMap<>(INTERMEDIATE_POOL_SIZE);
    private volatile long missedPacketsCtr;
    private volatile long packetsCtr;


    protected HiQSDRSource() {
        config = new Config();
        this.iqConverter = new Signed24BitIQConverter();
        stubBuffer = new byte[INTERMEDIATE_BUFFER_SIZE];
        for (int i = 0; i < INTERMEDIATE_POOL_SIZE; ++i) {
            ByteBuffer bb = ByteBuffer.allocateDirect(INTERMEDIATE_BUFFER_SIZE);
            if (bb.hasArray()) {
                arrayToBufferMap.put(bb.array(), bb);
                bufferToArrayMap.put(bb, bb.array());
            } else {
                byte[] arr = new byte[INTERMEDIATE_POOL_SIZE];
                arrayToBufferMap.put(arr, bb);
                bufferToArrayMap.put(bb, arr);
            }
            if (!intermediateBufferPool.offer(bb))
                Log.e(LOGTAG, "constructor: can not add intermediate buffer to the pool.");
        }
    }


    public HiQSDRSource(String host, int cmdPort, int rxPort, int txPort) {
        this();
        this.ipAddress = host;
        this.cmdPort = cmdPort;
        this.rxPort = rxPort;
        this.txPort = txPort;
    }

    public HiQSDRSource(Context context, SharedPreferences preferences) {
        this();
        this.iqConverter = new Signed24BitIQConverter();
        this.ipAddress = preferences.getString(context.getString(R.string.pref_hiqsdr_ip), "192.168.2.196");
        this.cmdPort = Integer.parseInt(preferences.getString(context.getString(R.string.pref_hiqsdr_command_port), "48248"));
        this.rxPort = Integer.parseInt(preferences.getString(context.getString(R.string.pref_hiqsdr_rx_port), "48247"));
        this.txPort = Integer.parseInt(preferences.getString(context.getString(R.string.pref_hiqsdr_tx_port), "48249"));

        config = new Config(
                Byte.parseByte(preferences.getString(context.getString(R.string.pref_hiqsdr_firmware), "2")));
        setFrequency(Long.parseLong(
                preferences.getString(context.getString(R.string.pref_hiqsdr_rx_frequency), "10000000")));
        tieTXFrequencyToRXFrequency(
                preferences.getBoolean(context.getString(R.string.pref_hiqsdr_tie_frequencies), true));
        setTxFrequency(Long.parseLong(
                preferences.getString(context.getString(R.string.pref_hiqsdr_tx_frequency), "10000000")));
        setTxMode(Byte.parseByte(
                preferences.getString(context.getString(R.string.pref_hiqsdr_tx_mode), Byte.toString(TX_MODE_HW_CONTNIOUS_WAVE))));
        setAntenna(
                Byte.parseByte(preferences.getString(context.getString(R.string.pref_hiqsdr_antenna), "0")));
        setSampleRate(Integer.parseInt(
                preferences.getString(context.getString(R.string.pref_hiqsdr_sampleRate)
                        , Integer.toString(MIN_SAMPLE_RATE))));
    }

    @Override
    public boolean open(Context context, Callback callback) {
        Log.i(LOGTAG, "open: called");
        this.context = context;
        this.callback = callback;
        workerThread = new Thread(this, NAME);
        // receiver thread is the most performance-dependant part, use high priority
        workerThread.setPriority(Thread.MAX_PRIORITY);
        workerThread.start();
        return true;
    }

    @Override
    public boolean isOpen() {
        return deviceResponded;
    }

    protected void changeState(int state) {
        this.state = state;
        if (receiverSelector != null) receiverSelector.wakeup();
        if (configSelector != null) configSelector.wakeup();
    }

    @Override
    public boolean close() {
        Log.i(LOGTAG, "close: called");
        changeState(STATE_CLOSE);
        return true;
    }

    @Override
    public String getName() {
        return NAME + " at " + ipAddress;
    }

    @Override
    public HiQSDRSource updatePreferences(Context context, SharedPreferences preferences) {
        String ip = preferences.getString(context.getString(R.string.pref_hiqsdr_ip), "192.168.2.196");
        int rxPort = Integer.parseInt(preferences.getString(context.getString(R.string.pref_hiqsdr_rx_port), "48247"));
        int cmdPort = Integer.parseInt(preferences.getString(context.getString(R.string.pref_hiqsdr_command_port), "48248"));

        if (!ipAddress.equals(ip) || this.rxPort != rxPort || this.cmdPort != cmdPort) {
            this.close();
            return new HiQSDRSource(context, preferences);
        }

        config.setFirmwareVersion(Byte.parseByte(preferences.getString(context.getString(R.string.pref_hiqsdr_firmware), "2")));
        final long rxFreq = Long.parseLong(
                preferences.getString(context.getString(R.string.pref_hiqsdr_rx_frequency), "10000000"));
        config.setRxFrequency(rxFreq);
        iqConverter.setFrequency(rxFreq);
        config.tieTxToRx(preferences.getBoolean(context.getString(R.string.pref_hiqsdr_tie_frequencies), true));
        config.setTxFrequency(Long.parseLong(
                preferences.getString(context.getString(R.string.pref_hiqsdr_tx_frequency), "10000000")));
        config.setTxMode(Byte.parseByte(
                preferences.getString(context.getString(R.string.pref_hiqsdr_tx_mode), Integer.toString(TX_MODE_HW_CONTNIOUS_WAVE))));
        config.setAntenna(
                Byte.parseByte(preferences.getString(context.getString(R.string.pref_hiqsdr_antenna), "0")));
        final int sampleRate = Integer.parseInt(
                preferences.getString(context.getString(R.string.pref_hiqsdr_sampleRate)
                        , Integer.toString(MIN_SAMPLE_RATE)));
        config.setSampleRate(sampleRate);
        iqConverter.setSampleRate(sampleRate);
        updateDeviceConfig();
        return this;
    }

    @Override
    public int getSampleRate() {
        return config.sampleRate;
    }

    @Override
    public void setSampleRate(int sampleRate) {
        try {
            config.setSampleRate(sampleRate);
            updateDeviceConfig();
            iqConverter.setSampleRate(sampleRate);
            Log.d(LOGTAG, "setSampleRate: sample rate set to " + sampleRate);
        } catch (IllegalArgumentException iae) {
            reportError(iae.getMessage());
        }

    }

    @Override
    public long getFrequency() {
        return config.rxTuneFrequency;
    }

    @Override
    public void setFrequency(long frequency) {
        config.setRxFrequency(frequency);
        iqConverter.setFrequency(frequency);
        updateDeviceConfig();
    }

    public void setFirmwareVersion(byte ver) {
        config.setFirmwareVersion(ver);
        updateDeviceConfig();
    }

    public void setTxFrequency(long frequency) {
        config.setTxFrequency(frequency);
        updateDeviceConfig();
    }

    public void tieTXFrequencyToRXFrequency(boolean tie) {
        config.tieTxToRx(tie);
        updateDeviceConfig();
    }

    public void setTxMode(byte mode) {
        config.setTxMode(mode);
        updateDeviceConfig();
    }

    public void setAntenna(byte ant) {
        config.setAntenna(ant);
        updateDeviceConfig();
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
        // sample rates sorted in descending order, start checking from the end
        for (int i = SAMPLE_RATES.length - 1; i >= 0; --i)
            if (SAMPLE_RATES[i] > sampleRate) {
                Log.d(LOGTAG, "getNextHigherOptimalSampleRate: " + sampleRate + "->" + SAMPLE_RATES[i]);
                return SAMPLE_RATES[i]; // return first met higher
            }
        Log.d(LOGTAG, "getNextHigherOptimalSampleRate: " + sampleRate + "->" + SAMPLE_RATES[0]);
        return SAMPLE_RATES[0]; // if not found - return first one
    }

    @Override
    public int getNextLowerOptimalSampleRate(int sampleRate) {
        for (int i = 0; i < SAMPLE_RATES.length; ++i)
            if (SAMPLE_RATES[i] < sampleRate) {
                Log.d(LOGTAG, "getNextLowerOptimalSampleRate: " + sampleRate + "->" + SAMPLE_RATES[i]);
                return SAMPLE_RATES[i]; // return first met lower
            }
        Log.d(LOGTAG, "getNextLowerOptimalSampleRate: " + sampleRate + "->" + SAMPLE_RATES[SAMPLE_RATES.length - 1]);
        return SAMPLE_RATES[SAMPLE_RATES.length - 1]; // if not found - return last one

    }

    @Override
    public int[] getSupportedSampleRates() {
        return SAMPLE_RATES;
    }

    @Override
    public int getSampledPacketSize() {
        return INTERMEDIATE_BUFFER_SIZE / 3 / 2; //% 3 bytes per sample, samples I and Q, making 2
    }

    protected int updatePacketIndex(byte index) {
        final int ret = (index + 256 - 1 - previousPacketIdx) & 0xff; // overflow magic
        previousPacketIdx = index;
        return ret;
    }

    @Override
    public byte[] getPacket(int timeout) {
        try {
            ByteBuffer buf = intermediateBufferQueue.poll(timeout, TimeUnit.MILLISECONDS);
            if (buf == null) {
                stubBuffer[0] = ++previousPacketIdx;
                return stubBuffer;
            }
            intermediateBufferBusyQueue.offer(buf);
            if (buf.hasArray())
                return buf.array();
            else {
                byte[] arr = bufferToArrayMap.get(buf);
                if (arr == null) {
                    arr = new byte[INTERMEDIATE_BUFFER_SIZE];
                    bufferToArrayMap.put(buf, arr);
                    arrayToBufferMap.put(arr, buf);
                }
                buf.get(arr);
                return arr;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null; // todo: fixme
    }

    private void updateDeviceConfig() {
        if (!deviceResponded) return;
        if (configChannel != null && configChannel.isConnected()) {
            changeState(STATE_UPDATE_CFG);
        } else reportError("updateDeviceConfig: Configuration channel is not opened.");
    }

    @Override
    public void returnPacket(byte[] buffer) {
        if (buffer == stubBuffer) return;
        if (intermediateBufferPool != null) {
            ByteBuffer buff = arrayToBufferMap.get(buffer);
            if (buff == null)
                for (ByteBuffer bb : intermediateBufferBusyQueue) {
                    if (bb.hasArray() && bb.array() == buffer) {
                        buff = bb;
                        intermediateBufferBusyQueue.remove(bb);
                        break;
                    }
                }
            if (buff == null) Log.e(LOGTAG, "returnPacket: somehow we lost buffer tied to this array");
            else intermediateBufferPool.offer(buff);
        } else {
            Log.e(LOGTAG, "returnPacket: Return queue is null");
        }
    }

    @Override
    public void startSampling() {
        if (!deviceResponded) {
            Log.d(LOGTAG, "startSampling: called from invalid state: " + state);
            reportError("startSampling: called before opened.");
            return;
        }
        if (receiverChannel != null && receiverChannel.isConnected()) {
            changeState(STATE_START_RECEIVING);
        } else reportError("startSampling: Receiver channel is not opened.");
    }


    @Override
    public void stopSampling() {
        if (receiverChannel != null && receiverChannel.isConnected()) {
            changeState(STATE_STOP_RECEIVING);
        } else Log.w(LOGTAG, "stopSampling: receiver channel is not opened.");
    }

    @Deprecated
    @Override
    public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket) {
        return this.iqConverter.fillPacketIntoSamplePacket(packet, samplePacket, RX_PAYLOAD_OFFSET);
    }

    @Deprecated
    @Override
    public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, long channelFrequency) {
        return this.iqConverter.mixPacketIntoSamplePacket(packet, samplePacket, RX_PAYLOAD_OFFSET, channelFrequency);
    }

    /**
     * Will forward an error message to the callback object
     *
     * @param msg error message
     */
    protected void reportError(String msg) {
        if (callback != null)
            callback.onIQSourceError(this, msg);
        else
            Log.e(LOGTAG, "reportError: Callback is null. (Error: " + msg + ")");
    }

    protected void reportReady() {
        if (callback != null)
            callback.onIQSourceReady(this);
        else
            Log.e(LOGTAG, "reportReady: Callback is null.");
    }

    public static long frequencyToTunePhase(long frequency) {
        return (long) (((frequency / (double) CLOCK_RATE) * (1L << 32)) + 0.5);
    }

    public static long tunePhaseToFrequency(long phase) {
        return (long) ((phase - 0.5) * CLOCK_RATE / (1L << 32));
    }

    public static int rxControlToSampleRate(byte rxCtrl) {
        return UDP_CLK_RATE / (rxCtrl + 1);
    }


    @Override
    public void run() {
        // resolve host
        try {
            Log.i(LOGTAG, "Host: " + ipAddress);
            InetAddress[] addrs = InetAddress.getAllByName(ipAddress);
            // find first reachable address
            for (InetAddress addr : addrs) {
                //Log.i(LOGTAG, "Trying address " + addr.toString());
                //if (addr.isReachable(5000)) { // don't use this, it won't work. I love Android too (not really).
                Log.i(LOGTAG, "Selected address " + addr.toString());
                remoteAddr = addr;
                //	break;
                //}
            }
        } catch (IOException ioe) {
            reportError(ioe.getMessage());
        }
        // init channels
        try {
            if (remoteAddr == null) {
                reportError("Could not resolve address");
            } else {
                Log.i(LOGTAG, "Creating command channel...");
                configChannel = DatagramChannel.open();
                configSelector = Selector.open();
                Log.i(LOGTAG, "Creating receiver channel...");
                receiverChannel = DatagramChannel.open();
                receiverSelector = Selector.open();
            }
        } catch (IOException ioe) {
            reportError(ioe.getMessage());
        }
        if (configChannel == null || receiverChannel == null) {
            reportError("Communication channels are not created");
            return;
        }

        Log.i(LOGTAG, "Connecting command channel...");
        cfgAddr = new InetSocketAddress(remoteAddr, cmdPort);
        try {
            configChannel.socket().setSendBufferSize(CFG_PACKET_SIZE * 2);
            configChannel.socket().setReceiveBufferSize(CFG_PACKET_SIZE * 2);
            configChannel.configureBlocking(false);
            configChannel.register(configSelector, SelectionKey.OP_READ);
            configChannel.connect(cfgAddr);
        } catch (IOException e) {
            reportError("Cannot connect to command channel");
            e.printStackTrace();
            return;
        }

        Log.i(LOGTAG, "Connecting receiver channel...");
        rxAddr = new InetSocketAddress(remoteAddr, rxPort);
        try {
            receiverChannel.socket().setSendBufferSize(CMD_PACKET_SIZE * 2);
            int rxBufSz = RX_PACKET_SIZE * INTERMEDIATE_BUFFER_SCALE * INTERMEDIATE_POOL_SIZE / 2;
            Log.d(LOGTAG, "Trying to set socket receive buffer size to " + rxBufSz);
            receiverChannel.socket().setReceiveBufferSize(rxBufSz);
            Log.d(LOGTAG, "socket receive buffer size set to " + receiverChannel.socket().getReceiveBufferSize());

            receiverChannel.configureBlocking(false);
            receiverChannel.register(receiverSelector, SelectionKey.OP_READ);
            receiverChannel.connect(rxAddr);
        } catch (IOException e) {
            reportError("Cannot connect to receiver channel");
            e.printStackTrace();
            return;
        }

        state = STATE_UPDATE_CFG;
        while (true) {
            switch (state) {
                case STATE_IDLE: {
                    //Log.v(LOGTAG, "state: IDLE");
                    SystemClock.sleep(16);
                    break;
                }
                case STATE_START_RECEIVING: {
                    Log.v(LOGTAG, "state: START_RECEIVING");
                    try {
                        if (receiverChannel != null && receiverChannel.isConnected()) {
                            START_RECEIVING_CMD.flip();
                            receiverChannel.write(START_RECEIVING_CMD);
                            receiving = true;
                            state = STATE_RECEIVING;
                        } else reportError("Receiver channel is not opened");
                    } catch (IOException e) {
                        reportError(e.getMessage());
                        e.printStackTrace();
                    }
                    break;
                }
                case STATE_RECEIVING: {
                    //Log.v(LOGTAG, "state: RECEIVING");
                    if (intermediateBuffer == null) {
                        Log.v(LOGTAG, "STATE_RECEIVING: pooling new intermediate buffer.");
                        intermediateBuffer = intermediateBufferPool.poll();
                        intermediateBuffer.clear();
                        scatteringBuffer[1] = intermediateBuffer;
                    }
                    headerBuffer.clear();
                    try {
                        //Log.v(LOGTAG, "STATE_RECEIVING: waiting for data to arrive.");
                        if (receiverSelector.select() > 0) { // if there are data to read from
                            receiverSelector.selectedKeys().clear();
                            //Log.v(LOGTAG, "STATE_RECEIVING: something arrived");
                            intermediateBuffer.mark(); // put a pillow in case something goes wrong
                            long read = receiverChannel.read(scatteringBuffer); // reads header to headerBuffer and payload to intermediateBuffer
                            //Log.v(LOGTAG, "STATE_RECEIVING: read "+read+" bytes from receiving channel");
                            headerBuffer.flip();
                            missedPacketsCtr += updatePacketIndex(headerBuffer.get());
                            packetsCtr++;
                            if (!intermediateBuffer.hasRemaining()) {
                                intermediateBuffer.flip(); // prepare for reading data from it
                                if (!intermediateBufferQueue.offer(intermediateBuffer)) {
                                    // return back to pool
                                    Log.w(LOGTAG, "Intermediate buffer queue overflow! Returning buffer back to pool.");
                                    if (!intermediateBufferPool.offer(intermediateBuffer)) {
                                        Log.w(LOGTAG, "Intermediate buffer pool overflow! Rewinding buffer and keeping for the next round.");
                                        intermediateBuffer.rewind();
                                    }
                                    intermediateBuffer = null;
                                }
                            }
                        }
                    } catch (IOException ioe) {
                        intermediateBuffer.reset(); // leap back in time-space before we did mistake trying to receive this packet.
                        Log.w(LOGTAG, "IOException while receiving. Loosing one packet.");
                        ioe.printStackTrace();
                    }
                    break;
                }
                case STATE_STOP_RECEIVING: {
                    Log.v(LOGTAG, "state: STOP_RECEIVING");
                    Log.d(LOGTAG, "Processed packets: " + packetsCtr);
                    Log.d(LOGTAG, "Missed packets: " + missedPacketsCtr);
                    Log.d(LOGTAG, "Missed packets ratio: " + (float) missedPacketsCtr * 100 / (packetsCtr + missedPacketsCtr) + '%');
                    packetsCtr = 0;
                    missedPacketsCtr = 0;
                    try {
                        if (receiverChannel != null && receiverChannel.isConnected()) {
                            STOP_RECEIVING_CMD.flip();
                            receiverChannel.write(STOP_RECEIVING_CMD);
                            receiving = false;
                            state = closing ? STATE_CLOSE : STATE_IDLE;
                        } else reportError("Receiver channel is not opened");
                    } catch (IOException e) {
                        reportError(e.getMessage());
                        e.printStackTrace();
                    }
                    break;
                }
                case STATE_UPDATE_CFG: {
                    Log.v(LOGTAG, "state: UPDATE_CFG");
                    try {
                        configChannel.write(config.getCfgBuffer());
                        int availableChannels;
                        if (deviceResponded)
                            availableChannels = configSelector.select(32);
                        else {
                            Log.i(LOGTAG, "Waiting for configuration echo...");
                            availableChannels = configSelector.select(5000);
                        }
                        if (availableChannels > 0) {
                            configSelector.selectedKeys().clear();
                            Log.v(LOGTAG, "read " + configChannel.read(config.cmdPacket) + " bytes on config channel");
                            config.cmdPacket.flip();
                            try {
                                config.fillFromPacket();
                            } catch (IllegalArgumentException iae) {
                                Log.w(LOGTAG, "UPDATE_CFG: received malformed configuration packet");
                            }
                            intermediateBufferQueue.drainTo(intermediateBufferPool);
                            if (!deviceResponded) {
                                Log.i(LOGTAG, "Device responded.");
                                deviceResponded = true;
                                state = STATE_IDLE;
                                reportReady();
                                break;
                            }
                        } else {
                            if (!deviceResponded) {
                                reportError("Timeout for devices response");
                                state = STATE_CLOSE;
                                break;
                            }
                        }
                    } catch (IOException e) {
                        reportError(e.getMessage());
                        e.printStackTrace();
                        state = STATE_CLOSE;
                        break;
                    }
                    state = receiving ? STATE_RECEIVING : STATE_IDLE;
                    break;
                }
                case STATE_CLOSE:
                    Log.v(LOGTAG, "state: CLOSE");
                    closing = true;
                    if (receiving) {
                        state = STATE_STOP_RECEIVING;
                        break;
                    }
                    try {
                        if (receiverSelector != null) receiverSelector.close();
                        receiverSelector = null;
                        if (receiverChannel != null) receiverChannel.close();
                        receiverChannel = null;
                        if (configSelector != null) configSelector.close();
                        configSelector = null;
                        if (configChannel != null) configChannel.close();
                        configChannel = null;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    state = STATE_EXIT;
                default:
                case STATE_EXIT:
                    Log.v(LOGTAG, "state: EXIT");
                    deviceResponded = false;
                    closing = false;
                    receiving = false;
                    return;
            }
        }
    }


    class Config {
        // received packet
        ByteBuffer cmdPacket = ByteBuffer.allocate(HiQSDRSource.CFG_PACKET_SIZE);
        // sent packet
        ByteBuffer ctrlCmdBuf = ByteBuffer.allocate(HiQSDRSource.CFG_PACKET_SIZE);
        private volatile boolean needToFillPacket = true;

        byte txPowerLevel;
        byte txControl;
        byte rxControl;
        byte firmwareVersion;
        byte preselector;
        byte attenuator;
        byte antenna;
        long rxTunePhase;
        long txTunePhase;

        int sampleRate;
        long txTuneFrequency;
        long rxTuneFrequency;

        boolean tieTX2RXFreq = true;

        public Config() {
            this((byte) 0x02);
        }

        public void fillFromPacket() throws IllegalArgumentException {
            fillFromPacket(cmdPacket);
        }

        public void fillFromPacket(ByteBuffer packet) throws IllegalArgumentException {
            packet.order(ByteOrder.LITTLE_ENDIAN);
            byte S = packet.get();
            byte t = packet.get();
            if (S != 0x53 || t != 0x74)
                throw new IllegalArgumentException("Malformed packet");
            rxTunePhase = packet.getInt();
            rxTuneFrequency = tunePhaseToFrequency(rxTunePhase);
            txTunePhase = packet.getInt();
            txTuneFrequency = tunePhaseToFrequency(txTunePhase);
            if (rxTuneFrequency == txTuneFrequency) {
                tieTX2RXFreq = true;
            }
            txPowerLevel = packet.get();
            txControl = packet.get();
            rxControl = packet.get();
            sampleRate = rxControlToSampleRate(rxControl);
            firmwareVersion = packet.get();
            if (firmwareVersion < 1) {
                preselector = 0;
                attenuator = 0;
                antenna = 0;
            } else {
                preselector = packet.get();
                attenuator = packet.get();
                antenna = packet.get();
            }
        }

        public Config(ByteBuffer packet) {
            this();
            fillFromPacket(packet);
        }

        public Config(byte fwVersion) {
            firmwareVersion = fwVersion;
            ctrlCmdBuf.order(ByteOrder.LITTLE_ENDIAN);
            cmdPacket.order(ByteOrder.LITTLE_ENDIAN);
        }

        public void setTxMode(byte mode) {
            switch (mode) {
                case TX_MODE_EXTENDED_IO:
                case TX_MODE_HW_CONTNIOUS_WAVE:
                    if (firmwareVersion == 0)
                        throw new UnsupportedOperationException("Specified mode is not supported by HiQSDR firmware v1.0");
                case TX_MODE_KEYED_CONTINIOUS_WAVE:
                case TX_MODE_RECEIVED_PTT:
                    txControl = mode;
                    break;
                default: throw new IllegalArgumentException("Unknown TX mode " + mode + '.');
            }
            needToFillPacket = true;
        }

        public void setTxPowerLevel(int powerLevel) {
            if (powerLevel > 255 || powerLevel < 0)
                throw new IllegalArgumentException("TxPowerLevel must be in range 0-255.");
            txPowerLevel = (byte) (powerLevel & 0xff);
            needToFillPacket = true;
        }

        public void setAntenna(byte ant) {
            if (firmwareVersion == 0)
                throw new UnsupportedOperationException("Antenna selection is not supported by HiQSDR fw v1.0");
            antenna = ant;
            needToFillPacket = true;
        }

        public void setFirmwareVersion(byte fwv) {
            if (fwv > 2 || fwv < 0)
                throw new IllegalArgumentException("Supported fw versions: 0, 1, 2");
            firmwareVersion = fwv;
            needToFillPacket = true;
        }

        public void setRxFrequency(long frequency) {
            rxTuneFrequency = frequency;
            rxTunePhase = frequencyToTunePhase(frequency);
            needToFillPacket = true;
        }

        public void setTxFrequency(long frequency) {
            txTuneFrequency = frequency;
            txTunePhase = frequencyToTunePhase(frequency);
            needToFillPacket = true;
        }

        public void tieTxToRx(boolean tie) {
            tieTX2RXFreq = tie;
            needToFillPacket = true;
        }

        public void setSampleRate(int sampleRate) throws IllegalArgumentException {
            // lazy
            if (this.sampleRate == sampleRate)
                return;

            if (sampleRate <= 0)
                throw new IllegalArgumentException("Sample rate must be positive number and one of supported values.");
            byte code = -1;
            for (int i = 0; i < SAMPLE_RATES.length; ++i) {
                if (SAMPLE_RATES[i] == sampleRate) {
                    code = SAMPLE_RATE_CODES[i];
                    break;
                }
            }
            if (code < 0)
                throw new IllegalArgumentException("Specified sample rate (" + sampleRate + " is not supported");
            this.sampleRate = sampleRate;
            rxControl = code;
            needToFillPacket = true;
        }

        protected synchronized void fillCtrlPacket() {
            if (needToFillPacket) {
                ctrlCmdBuf.position(0);
                ctrlCmdBuf
                        .put((byte) 0x53) // 'S'
                        .put((byte) 0x74) // 't'
                        .putInt((int) (rxTunePhase))
                        .putInt((int) (tieTX2RXFreq ? rxTunePhase : txTunePhase))
                        .put(txPowerLevel)
                        .put(txControl)
                        .put(rxControl)
                        .put(firmwareVersion);
                if (firmwareVersion < 1) {
                    ctrlCmdBuf
                            .put((byte) 0)
                            .put((byte) 0)
                            .put((byte) 0);
                } else {
                    ctrlCmdBuf
                            .put(preselector)
                            .put(attenuator)
                            .put(antenna);
                }
                ctrlCmdBuf
                        .put((byte) 0)
                        .put((byte) 0)
                        .put((byte) 0)
                        .put((byte) 0)
                        .put((byte) 0);
                ctrlCmdBuf.flip();
                needToFillPacket = false;
            }
        }

        public ByteBuffer getCfgBuffer() {
            fillCtrlPacket();
            return ctrlCmdBuf;
        }
    }

}

