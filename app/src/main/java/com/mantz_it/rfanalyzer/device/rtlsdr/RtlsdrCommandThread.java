package com.mantz_it.rfanalyzer.device.rtlsdr;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.AutomaticGainSwitchControl;
import com.mantz_it.rfanalyzer.sdr.controls.FrequencyCorrectionControl;
import com.mantz_it.rfanalyzer.sdr.controls.IntermediateFrequencyGainControl;
import com.mantz_it.rfanalyzer.sdr.controls.ManualGainControl;
import com.mantz_it.rfanalyzer.sdr.controls.ManualGainSwitchControl;
import com.mantz_it.rfanalyzer.sdr.controls.MixerFrequency;
import com.mantz_it.rfanalyzer.sdr.controls.RXFrequency;
import com.mantz_it.rfanalyzer.sdr.controls.RXSampleRate;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * This thread will initiate the connection to the rtl_tcp instance and then send commands to
 * it. Commands can be queued for execution by other threads
 */
class RtlsdrCommandThread extends Thread {
private static final String LOGTAG = "[RTL-SDR]:CommandThread";

String magic = null;
private RtlsdrSource rtlsdrSource;
String threadName = null;    // We save the thread name to check against it in the close() method
private ArrayBlockingQueue<byte[]> commandQueue = null;
private static final int COMMAND_QUEUE_SIZE = 20;
private ArrayBlockingQueue<byte[]> frequencyChangeCommandQueue = null;    // separate queue for frequency changes (work-around)
private boolean stopRequested = false;

private Socket socket = null;
private String ipAddress;
private int port;
private RXFrequency rxFrequency;
private MixerFrequency mixerFrequency;
private RXSampleRate rxSampleRate;
private ManualGainSwitchControl manualGainSwitch;
private AutomaticGainSwitchControl automaticGainSwitch;
private ManualGainControl manualGain;
private IntermediateFrequencyGainControl ifGain;
private FrequencyCorrectionControl frequencyCorrection;

public RtlsdrCommandThread(RtlsdrSource rtlsdrSource, String ipAddress, int port, MixerFrequency mixerFrequency) {
	super("RtlSdr Command Thread");
	this.rtlsdrSource = rtlsdrSource;
	this.ipAddress = ipAddress;
	this.port = port;
	this.mixerFrequency = mixerFrequency;
	this.rxFrequency = rtlsdrSource.getControl(RXFrequency.class);
	this.rxSampleRate = rtlsdrSource.getControl(RXSampleRate.class);
	this.manualGainSwitch = rtlsdrSource.getControl(ManualGainSwitchControl.class);
	this.automaticGainSwitch = rtlsdrSource.getControl(AutomaticGainSwitchControl.class);
	// Create command queue:
	this.commandQueue = new ArrayBlockingQueue<>(COMMAND_QUEUE_SIZE);
	this.frequencyChangeCommandQueue = new ArrayBlockingQueue<>(1);    // work-around
}

public void stopCommandThread() {
	this.stopRequested = true;
}

/**
 * Will schedule the command (put it into the command queue
 *
 * @param command 5 byte command array (see rtl_tcp documentation)
 * @return true if command has been scheduled;
 */
private boolean executeCommand(byte[] command) {
	Log.d(LOGTAG, "executeCommand: Queuing command: " + RtlsdrCommand.values()[command[0] + 1]);
	if (commandQueue.offer(command))
		return true;

	// Queue is full
	// todo: maybe flush the queue? for now just error:
	Log.e(LOGTAG, "executeCommand: command queue is full!");
	return false;
}

public boolean executeCommand(RtlsdrCommand command, int argument) {
	if (command == RtlsdrCommand.SET_FREQUENCY) {
		executeFrequencyChangeCommand(command.marshall(argument));
		return true;
	}
	return executeCommand(command.marshall(argument));
}

public boolean executeCommand(RtlsdrCommand command, boolean argument) {
	return executeCommand(command.marshall(argument ? 0x01 : 0x00));
}

public boolean executeCommand(RtlsdrCommand command, short argument1, short argument2) {
	return executeCommand(command.marshall(argument1, argument2));
}

/**
 * Work-around:
 * Frequency changes happen very often and if too many of these commands are sent to the driver
 * it will lag and eventually crash. To prevent this, we have a separate commandQueue only for
 * frequency changes. This queue has size 1 and executeFrequencyChangeCommand() will ensure that
 * it contains always the latest frequency change command. The command thread will always sleep 250 ms
 * after executing a frequency change command to prevent a high rate of commands.
 *
 * @param command 5 byte command array (see rtl_tcp documentation)
 */
private void executeFrequencyChangeCommand(byte[] command) {
	// remove any waiting frequency change command from the queue (not used any more):
	frequencyChangeCommandQueue.poll();
	frequencyChangeCommandQueue.offer(command);    // will always work
}

/**
 * Called from run(); will setup the connection to the rtl_tcp instance
 */
private boolean connect(int timeoutMillis) {
	if (socket != null) {
		Log.e(LOGTAG, "connect: Socket is still connected");
		return false;
	}

	// Connect to remote/local rtl_tcp
	try {
		long timeoutTime = System.currentTimeMillis() + timeoutMillis;
		while (!stopRequested && socket == null && System.currentTimeMillis() < timeoutTime) {
			try {
				socket = new Socket(ipAddress, port);
			}
			catch (IOException e) {
				// ignore...
			}
			sleep(100);
		}

		if (socket == null) {
			if (stopRequested)
				Log.i(LOGTAG, "CommandThread: (connect) command thread stopped while connecting.");
			else
				Log.e(LOGTAG, "CommandThread: (connect) hit timeout");
			return false;
		}

		// Set socket options:
		socket.setTcpNoDelay(true);
		socket.setSoTimeout(1000);

		rtlsdrSource.inputStream = socket.getInputStream();
		rtlsdrSource.outputStream = socket.getOutputStream();
		byte[] buffer = new byte[4];

		// Read magic value:
		if (rtlsdrSource.inputStream.read(buffer, 0, buffer.length) != buffer.length) {
			Log.e(LOGTAG, "CommandThread: (connect) Could not read magic value");
			return false;
		}
		magic = new String(buffer, "ASCII");

		// Read tuner type:
		if (rtlsdrSource.inputStream.read(buffer, 0, buffer.length) != buffer.length) {
			Log.e(LOGTAG, "CommandThread: (connect) Could not read tuner type");
			return false;
		}
		rtlsdrSource.setTuner(RtlsdrTuner.valueOf(buffer[3]));
		if (rtlsdrSource.getTuner() == RtlsdrTuner.UNKNOWN) {
			Log.e(LOGTAG, "CommandThread: (connect) Unknown or invalid tuner type");
			return false;
		}

		// Read gain count (only for debugging. value is not used for now)
		if (rtlsdrSource.inputStream.read(buffer, 0, buffer.length) != buffer.length) {
			Log.e(LOGTAG, "CommandThread: (connect) Could not read gain count");
			return false;
		}

		Log.i(LOGTAG, "CommandThread: (connect) Connected to RTL-SDR"
		              + " (Tuner: " + rtlsdrSource.getTuner()
		              + ";  magic: " + magic
		              + ";  gain count: " + buffer[3]
		              + ") at " + ipAddress + ":" + port);

		// Update source name with the new information:
		rtlsdrSource.setName("RTL-SDR (" + rtlsdrSource.getTuner() + ") at " + ipAddress + ":" + port);

		mixerFrequency.set(rxFrequency.get() + rxFrequency.getFrequencyShift());

		if (manualGain.size() > 0)
			manualGain.setByIndex(0);
		else
			manualGain.set(0);
		updateControls();
		return true;

	}
	catch (UnknownHostException e) {
		Log.e(LOGTAG, "CommandThread: (connect) Unknown host: " + ipAddress);
		rtlsdrSource.reportError("Unknown host: " + ipAddress);
	}
	catch (IOException e) {
		Log.e(LOGTAG, "CommandThread: (connect) Error while connecting to rtlsdr://" + ipAddress + ":" + port + " : " + e.getMessage());
	}
	catch (InterruptedException e) {
		Log.e(LOGTAG, "CommandThread: (connect) Interrupted.");
	}
	return false;
}

private void updateControls() {// Set all parameters:
	// Frequency:
	rxFrequency.set(rxFrequency.get());

	// Sample Rate:
	rxSampleRate.set(rxSampleRate.get());
	// Gain Mode:
	manualGainSwitch.set(manualGainSwitch.get());

	// Gain:
	if (manualGainSwitch.get()) {
		manualGain.set(manualGain.get());
		//executeCommand(RtlsdrCommand.SET_GAIN, rtlsdrSource.gain);
		// IFGain:
		if (rtlsdrSource.getTuner() == RtlsdrTuner.E4000)
			ifGain.set(ifGain.get());
	}

	// Frequency Correction:
	frequencyCorrection.set(frequencyCorrection.get());
	//executeCommand(RtlsdrCommand.SET_FREQ_CORRECTION, rtlsdrSource.frequencyCorrection);

	// AGC mode:
	automaticGainSwitch.set(automaticGainSwitch.get());
}

public void run() {
	Log.i(LOGTAG, "CommandThread started (Thread: " + this.getName() + ")");
	threadName = this.getName();
	byte[] nextCommand = null;

	// Perfom "device open". This means connect to the rtl_tcp instance; get the information
	if (connect(10000)) {    // 10 seconds for the user to accept permission request
		// report that the device is ready:
		rtlsdrSource.onSourceReady();
	} else {
		if (!stopRequested) {
			Log.e(LOGTAG, "CommandThread: (open) connect reported error.");
			rtlsdrSource.reportError("Couldn't connect to rtl_tcp instance");
			stopRequested = true;
		}
		// else: thread was stopped while connecting...
	}

	// poll commands from queue and send them over the socket in loop:
	while (!stopRequested && rtlsdrSource.outputStream != null) {
		try {
			nextCommand = commandQueue.poll(100, TimeUnit.MILLISECONDS);

			// Work-around:
			// Frequency changes happen very often and if too many of these commands are sent to the driver
			// it will lag and eventually crash. To prevent this, we have a separate commandQueue only for
			// frequency changes. This queue has size 1 and executeFrequencyChangeCommand() will ensure that
			// it contains always the latest frequency change command. The command thread will always sleep 100 ms
			// after executing a frequency change command to prevent a high rate of commands.
			if (nextCommand == null)
				nextCommand = frequencyChangeCommandQueue.poll(); // check for frequency change commands:

			if (nextCommand == null)
				continue;
			rtlsdrSource.outputStream.write(nextCommand);
			Log.d(LOGTAG, "CommandThread: Command was sent: " + RtlsdrCommand.commandName(nextCommand[0]));
		}
		catch (IOException e) {
			Log.e(LOGTAG, "CommandThread: Error while sending command (" + RtlsdrCommand.commandName(nextCommand[0]) + "): " + e.getMessage());
			rtlsdrSource.reportError("Error while sending command: " + RtlsdrCommand.commandName(nextCommand[0]));
			break;
		}
		catch (InterruptedException e) {
			Log.e(LOGTAG, "CommandThread: Interrupted while sending command (" +
			              (nextCommand == null || nextCommand.length < 1 ? "null" : RtlsdrCommand.commandName(nextCommand[0])) + ")");
			rtlsdrSource.reportError("Interrupted while sending command: " + RtlsdrCommand.commandName(nextCommand[0]));
			break;
		}
	}

	// Clean up:
	if (socket != null) {
		try {
			socket.close();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	socket = null;
	rtlsdrSource.inputStream = null;
	rtlsdrSource.outputStream = null;
	rtlsdrSource.commandThreadClosed(this);
	Log.i(LOGTAG, "CommandThread stopped (Thread: " + this.getName() + ")");
}
}
