package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by pavlus on 10.06.16.
 */
public class HiQSDRSource implements IQSourceInterface {
	private static final String LOGTAG = "HiQSDRSource";
	private static final String NAME = "HiQSDRSource";

	protected static final byte MAXIMUM_DECIMATION = 40;
	protected static final int CLOCK_RATE = 122880000;
	protected static final int UDP_CLK_RATE = CLOCK_RATE / 64;

	protected static int MIN_SAMPLE_RATE = -1;// = 48000;
	protected static int MAX_SAMPLE_RATE = -1;// 1920000;
	protected static int[] SAMPLE_RATES = null;
	protected static byte[] SAMPLE_RATE_CODES = null;

	// TODO: determine exact frequency limits
	protected static final int MIN_FREQUENCY = 100000; // 100 kHz
	protected static final int MAX_FREQUENCY = CLOCK_RATE / 2; // (?)61 MHz, but there is info, that 66 MHz

	protected static final int RX_PACKET_SIZE = 1442;
	protected static final int CMD_PACKET_SIZE = 22;
	//---------------------------------------
	// todo: check names and actual modes
	public static final byte TX_MODE_INVALID = 0x0;
	public static final byte TX_MODE_KEYED_CONTINIOUS_WAVE = 0x1;
	public static final byte TX_MODE_RECEIVED_PTT = 0x2;
	public static final byte TX_MODE_EXTENDED_IO = 0x4;
	public static final byte TX_MODE_HW_CONTNIOUS_WAVE = 0x8;
	protected Config config;
	protected String ipAddress;
	protected int cmdPort;
	protected int rxPort;
	protected int txPort;
	//---------------------------------------
	private AsyncTask<Void, Void, Void> openerTask;
	protected Callback callback;
	protected Context context;
	protected UDPSessionThread commandThread;
	protected UDPSessionThread receiverThread;
	//protected UDPSessionThread transmitterThread;
	protected IQConverter iqConverter;
	protected InetAddress remoteAddr;
	private boolean deviceResponded = false;
	protected byte previousPacketIdx = 0;
	protected int stub_sent_counter = 0;
	protected int MAX_STUB_SENT = 10;
	// commands
	static final byte[] START_RECEIVING_CMD = {'r', 'r'};
	static final byte[] STOP_RECEIVING_CMD = {'s', 's'};
	// stub
	static final byte[] stub_packet = new byte[RX_PACKET_SIZE];

	public static void initArrays() {
		if (SAMPLE_RATE_CODES == null) {
			byte[] tmp = new byte[MAXIMUM_DECIMATION];
			int cnt = 0;
			for (byte i = 1; i <= MAXIMUM_DECIMATION; i++)
				if (UDP_CLK_RATE % i == 0) {
					tmp[cnt++] = (byte) (i + 1);
				}
			SAMPLE_RATE_CODES = Arrays.copyOf(tmp, cnt);
		}
		if (SAMPLE_RATES == null) {
			SAMPLE_RATES = new int[SAMPLE_RATE_CODES.length];
			for (int i = 0; i < SAMPLE_RATES.length; ++i)
				SAMPLE_RATES[i] = UDP_CLK_RATE / (SAMPLE_RATE_CODES[i] - 1);
			MAX_SAMPLE_RATE = SAMPLE_RATES[0];
			MIN_SAMPLE_RATE = SAMPLE_RATES[SAMPLE_RATES.length - 1];
		}
	}

	public HiQSDRSource() {
		initArrays();
		config = new Config();
		this.iqConverter = new Unsigned24BitIQConverter();
	}

	public HiQSDRSource(String host, int cmdPort, int rxPort, int txPort) {
		this();
		this.ipAddress = host;
		this.cmdPort = cmdPort;
		this.rxPort = rxPort;
		this.txPort = txPort;
	}

	public HiQSDRSource(Context context, SharedPreferences preferences) {
		initArrays();
		this.iqConverter = new Unsigned24BitIQConverter();

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
		(openerTask = new SourceOpener()).execute();
		return true;
	}

	@Override
	public boolean isOpen() {
		return deviceResponded
		       && receiverThread != null
		       && commandThread != null
		       && receiverThread.socket != null
		       && commandThread.socket != null
		       && receiverThread.isRunning()
		       && commandThread.isRunning();
	}

	@Override
	public boolean close() {
		Log.i(LOGTAG, "close: called");

		if (receiverThread != null) {
			stopSampling();
			receiverThread.shutdown();
			receiverThread = null;
		}

		if (commandThread != null) {
			commandThread.shutdown();
			commandThread = null;
		}
		deviceResponded = false;
		stub_sent_counter = 0;
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
				preferences.getString(context.getString(R.string.pref_hiqsdr_tx_mode), Integer.toString( TX_MODE_HW_CONTNIOUS_WAVE))));
		config.setAntenna(
				Byte.parseByte(preferences.getString(context.getString(R.string.pref_hiqsdr_antenna), "0")));
		final int sampleRate = Integer.parseInt(
				preferences.getString(context.getString(R.string.pref_hiqsdr_sampleRate)
						, Integer.toString( MIN_SAMPLE_RATE)));
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
	public int getPacketSize() {
		return RX_PACKET_SIZE;
	}

	protected int updatePacketIndex(byte[] buff) {
		final int ret = (buff[0] + 256 - 1 - previousPacketIdx) & 0xff; // overflow magic
		previousPacketIdx = buff[0];
		return ret;
	}

	@Override
	public byte[] getPacket(int timeout) {
		if (receiverThread.receivedPackets != null) {
			try {
				final byte[] packet = receiverThread.receivedPackets.poll(timeout, TimeUnit.MILLISECONDS);
				if (packet == null) {
					stub_sent_counter++;
					//Log.d(LOGTAG, "getPacket: returning stub packet #" + stub_sent_counter
					//              + " after waiting for " + timeout + " ms.");
					stub_packet[0] = ++previousPacketIdx;
					if (stub_sent_counter >= MAX_STUB_SENT) {
						deviceResponded = false;
						reportError("Missed too much packets from source. Stopped.");
						return null;
					}
				} else {
					final int missed_packets = updatePacketIndex(packet);
					stub_sent_counter = 0;
					//if (missed_packets != 0)
					//	Log.v(LOGTAG, "getPacket: missed " + missed_packets + (missed_packets == 1 ? " packet" : " packets"));
				}

				return packet == null ? stub_packet : packet;
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "getPacket: Interrupted while polling packet from queue: " + e.getMessage());
			}
		} else {
			Log.e(LOGTAG, "getPacket: Queue is null");
		}
		Log.d(LOGTAG, "getPacket: returning null");
		return null;
	}

	private void updateDeviceConfig() {
		if (isOpen()) {
			commandThread.send(config.getCmdPacket());
		}
	}

	@Override
	public void returnPacket(byte[] buffer) {
		if (buffer == stub_packet) {
			//Log.v(LOGTAG, "returnPacket: called for stub packet.");
			return;
		}
		if (receiverThread.receivePacketsPool != null) {
			receiverThread.receivePacketsPool.offer(buffer);
		} else {
			Log.e(LOGTAG, "returnPacket: Return queue is null");
		}
	}

	@Override
	public void startSampling() {
		stub_sent_counter = 0;
		if (receiverThread != null && receiverThread.isRunning()) {
			//Log.i(LOGTAG, "startSampling: sending receive request");
			// try few times in case packet got lost
			for (int i = 0; i < 3; ++i) {
				receiverThread.send(START_RECEIVING_CMD);
			}
		} else Log.w(LOGTAG, "startSampling: receiverThread is null or not working.");
	}


	@Override
	public void stopSampling() {
		stub_sent_counter = 0;
		if (receiverThread != null && receiverThread.isRunning()) {
			//Log.i(LOGTAG, "stopSampling: sending stop receiving request");
			// try few times in case packet got lost
			for (int i = 0; i < 3; ++i) {
				receiverThread.send(STOP_RECEIVING_CMD);
			}
		} else Log.w(LOGTAG, "stopSampling: receiver thread is null or not working.");
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
		return (long) (((frequency / (float) CLOCK_RATE) * (1L << 32)) + 0.5);
	}

	private class SourceOpener extends AsyncTask<Void, Void, Void> {
		private String resultMsg = "Success";

		@Override
		protected Void doInBackground(Void... params) {
			deviceResponded = false;
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
				if (remoteAddr == null) {
					resultMsg = "Could not resolve address";
				} else {
					commandThread = new UDPSessionThread(remoteAddr, cmdPort, CMD_PACKET_SIZE);
					receiverThread = new UDPSessionThread(remoteAddr, rxPort, RX_PACKET_SIZE);
				}
			} catch (IOException ioe) {
				resultMsg = ioe.getMessage();
				return null;
			}
			// open
			if (commandThread == null || receiverThread == null) {
				resultMsg = "Communication threads are not created";
				return null;
			} else {
				if (!commandThread.connect()) {
					resultMsg = "Cannot connect to command channel";
					return null;
				}
				if (!receiverThread.connect()) {
					resultMsg = "Cannot connect to receiver channel";
					return null;
				}
				config.fillCtrlPacket();
				commandThread.send(config.cmdPacket);
				try {
					if (null != commandThread.receivedPackets.poll(5000, TimeUnit.MILLISECONDS)) // 5 seconds more than enough
						deviceResponded = true;
				} catch (InterruptedException e) {
					resultMsg = "Timeout for devices response";
					e.printStackTrace();
				}

			}
			return null;
		}

		@Override
		protected void onPostExecute(Void aVoid) {
			if (deviceResponded) {
				reportReady();
			} else reportError("Could not open HiQSDR source: " + resultMsg);
			super.onPostExecute(aVoid);
		}
	}

	protected class Config {
		byte[] cmdPacket = new byte[22];
		private byte[] ctrlCmdBuf = new byte[22];
		private volatile boolean needToFillPacket = true;

		int txPowerLevel;
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

		public Config(byte fwVersion) {
			firmwareVersion = fwVersion;
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
			txPowerLevel = powerLevel;
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
				ctrlCmdBuf[0] = 'S';
				ctrlCmdBuf[1] = 't';
				ctrlCmdBuf[2] = (byte) (rxTunePhase & 0xff);
				ctrlCmdBuf[3] = (byte) (rxTunePhase >> 8 & 0xff);
				ctrlCmdBuf[4] = (byte) (rxTunePhase >> 16 & 0xff);
				ctrlCmdBuf[5] = (byte) (rxTunePhase >> 24 & 0xff);
				if (tieTX2RXFreq) {
					ctrlCmdBuf[6] = ctrlCmdBuf[2];
					ctrlCmdBuf[7] = ctrlCmdBuf[3];
					ctrlCmdBuf[8] = ctrlCmdBuf[4];
					ctrlCmdBuf[9] = ctrlCmdBuf[5];
				} else {
					ctrlCmdBuf[6] = (byte) (txTunePhase & 0xff);
					ctrlCmdBuf[7] = (byte) (txTunePhase >> 8 & 0xff);
					ctrlCmdBuf[8] = (byte) (txTunePhase >> 16 & 0xff);
					ctrlCmdBuf[9] = (byte) (txTunePhase >> 24 & 0xff);
				}
				ctrlCmdBuf[10] = (byte) (txPowerLevel & 0xff);
				ctrlCmdBuf[11] = txControl;
				ctrlCmdBuf[12] = rxControl;
				ctrlCmdBuf[13] = firmwareVersion;
				if (firmwareVersion < 1) {
					ctrlCmdBuf[14] = 0;
					ctrlCmdBuf[15] = 0;
					ctrlCmdBuf[16] = 0;
				} else {
					ctrlCmdBuf[14] = preselector;
					ctrlCmdBuf[15] = attenuator;
					ctrlCmdBuf[16] = antenna;
				}
				ctrlCmdBuf[17] = 0;
				ctrlCmdBuf[18] = 0;
				ctrlCmdBuf[19] = 0;
				ctrlCmdBuf[20] = 0;
				ctrlCmdBuf[21] = 0;
				final byte[] tmp = cmdPacket;
				cmdPacket = ctrlCmdBuf;
				ctrlCmdBuf = tmp;
				needToFillPacket = false;
			}
		}

		public byte[] getCmdPacket() {
			fillCtrlPacket();
			return cmdPacket;
		}
	}

	protected class UDPSessionThread extends Thread {
		final String threadName;
		final int remotePort;
		final InetSocketAddress socketAddr;
		volatile boolean continueWork;
		DatagramSocket socket;
		ArrayBlockingQueue<DatagramPacket> packetsToSend;
		ArrayBlockingQueue<byte[]> receivedPackets;
		ArrayBlockingQueue<byte[]> receivePacketsPool;

		final int PACKETS_SEND_POOL_SIZE = 16;
		final int PACKETS_RECV_POOL_SIZE = 100;
		//private final int MAX_SEND_BUFFER_SIZE = 22;
		final int MAX_RECV_BUFFER_SIZE;
		final int SOCK_RECV_TIMEOUT = (1000 / PACKETS_RECV_POOL_SIZE) + 1; //+1 in case something changes and we get 0

		public UDPSessionThread(InetAddress addr, int port, int recvBuffSize) {
			super("HiQSDR UDPSessionThread (" + addr.getCanonicalHostName() + ':' + port + ')');
			threadName = "HiQSDR UDPSessionThread (" + addr.getCanonicalHostName() + ':' + port + ')';
			remoteAddr = addr;
			this.remotePort = port;
			//Log.i("UDPSessionThread", "Addr: "+addr.getCanonicalHostName()+", port: "+port);
			socketAddr = new InetSocketAddress(remoteAddr, remotePort);
			//Log.i("UDPSessionThread", "socketAddr="+socketAddr.toString());
			MAX_RECV_BUFFER_SIZE = recvBuffSize;
			packetsToSend = new ArrayBlockingQueue<>(PACKETS_SEND_POOL_SIZE);
			receivedPackets = new ArrayBlockingQueue<>(PACKETS_RECV_POOL_SIZE);
			receivePacketsPool = new ArrayBlockingQueue<>(PACKETS_RECV_POOL_SIZE);
			for (int i = 0; i < PACKETS_RECV_POOL_SIZE; ++i) {
				receivePacketsPool.offer(new byte[RX_PACKET_SIZE]);
			}
		}

		public boolean isRunning() {
			return continueWork;
		}

		public void shutdown() {
			continueWork = false;
		}

		public void send(byte[] buffer) {
			if (!continueWork) {
				Log.w(LOGTAG, "send: Won't send packet -- session is shutting down or hasn't started yet.");
			}
			if (socket == null) {
				Log.w(LOGTAG, "send: Won't send packet -- socket is null(probably not connected)");
				return;
			}
			try {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length, socketAddr);
				if (!packetsToSend.offer(packet)) {
					Log.w(LOGTAG, "send: Offering packet to send queue rejected, try again.");
				}// else Log.i(LOGTAG, "send: packet successfully put into send queue");
			} catch (SocketException se) {
				reportError(se.getMessage());
			}
		}

		boolean connect() {
			if (continueWork) {
				Log.e(LOGTAG, "connect: Still connected");
				return false;
			}
			try {
				socket = new DatagramSocket();
				socket.setReceiveBufferSize(MAX_RECV_BUFFER_SIZE);
				socket.setSoTimeout(SOCK_RECV_TIMEOUT);
				socket.connect(socketAddr);
				continueWork = true;
				super.start();
				return true;
			} catch (SocketException se) {
				se.printStackTrace();
				reportError(se.getMessage());
			}
			Log.e(LOGTAG, "Not connected.");
			return false;
		}

		@Override
		public void run() {
			DatagramPacket sendPacket;
			DatagramPacket recvPacket;
			while (continueWork || packetsToSend.peek() != null) {
				// send all packets
				while (null != (sendPacket = packetsToSend.poll())) {
					try {/*
						Log.i(LOGTAG, "Sending packet to "
						              + sendPacket.getAddress().getCanonicalHostName()
						              + ':' + sendPacket.getPort());
						              */
						socket.send(sendPacket);
					} catch (IOException ioe) {
						ioe.printStackTrace();
						reportError(ioe.getMessage());
					}
				}

				byte[] recvBuff = receivePacketsPool.poll();
				if (recvBuff == null) {
					recvBuff = new byte[MAX_RECV_BUFFER_SIZE];
					//Log.w(LOGTAG, "run: receivePacketsPool underflow!");
				}
				try {
					recvPacket = new DatagramPacket(recvBuff, MAX_RECV_BUFFER_SIZE, socketAddr);
					try {
						socket.receive(recvPacket);
						receivedPackets.offer(recvPacket.getData());
					} catch (SocketTimeoutException ste) {
						// nothing to receive, return packet back to the pool
						receivePacketsPool.offer(recvBuff);
					} catch (IOException e) {
						e.printStackTrace();
						reportError(e.getMessage());
					}
				} catch (SocketException e) {
					e.printStackTrace();
					reportError(e.getMessage());
				}
			}
			socket.close();
			packetsToSend.clear(); // just precaution, should be empty at this point
			socket = null;
		}
	}

}
