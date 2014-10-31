package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * <h1>RF Analyzer - Main Activity</h1>
 *
 * Module:      MainActivity.java
 * Description: Main Activity of the RF Analyzer
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class MainActivity extends Activity implements IQSourceInterface.Callback, AnalyzerSurface.CallbackInterface {

	private MenuItem mi_startStop = null;
	private MenuItem mi_demodulationMode = null;
	private FrameLayout fl_analyzerFrame = null;
	private AnalyzerSurface analyzerSurface = null;
	private AnalyzerProcessingLoop analyzerProcessingLoop = null;
	private IQSourceInterface source = null;
	private Scheduler scheduler = null;
	private Demodulator demodulator = null;
	private SharedPreferences preferences = null;
	private Bundle savedInstanceState = null;
	private Process logcat = null;
	private boolean running = false;
	private int demodulationMode = Demodulator.DEMODULATION_OFF;

	private static final String LOGTAG = "MainActivity";
	private static final int FILE_SOURCE = 0;
	private static final int HACKRF_SOURCE = 1;
	private static final int RTLSDR_SOURCE = 2;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		this.savedInstanceState = savedInstanceState;

		// Set default Settings on first run:
		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

		// Get reference to the shared preferences:
		preferences = PreferenceManager.getDefaultSharedPreferences(this);

		// Overwrite defaults for file paths in the preferences:
		String extStorage = Environment.getExternalStorageDirectory().getAbsolutePath();	// get the path to the ext. storage
		// File Source file:
		String defaultFile = getString(R.string.pref_filesource_file_default);
		if(preferences.getString(getString(R.string.pref_filesource_file), "").equals(defaultFile))
			preferences.edit().putString(getString(R.string.pref_filesource_file), extStorage + "/" + defaultFile).apply();
		// Log file:
		defaultFile = getString(R.string.pref_logfile_default);
		if(preferences.getString(getString(R.string.pref_logfile), "").equals(defaultFile))
			preferences.edit().putString(getString(R.string.pref_logfile), extStorage + "/" + defaultFile).apply();

		// Start logging if enabled:
		if(preferences.getBoolean(getString(R.string.pref_logging), false)) {
			try{
				File logfile = new File(preferences.getString(getString(R.string.pref_logfile), ""));
				logfile.getParentFile().mkdir();	// Create folder
				logcat = Runtime.getRuntime().exec("logcat -f " + logfile);
				Log.i("MainActivity", "onCreate: started logcat ("+logcat.toString()+") to " + logfile);
			} catch (Exception e) {
				Log.e("MainActivity", "onCreate: Failed to start logging!");
			}
		}

		// Get references to the GUI components:
		fl_analyzerFrame = (FrameLayout) findViewById(R.id.fl_analyzerFrame);

		// Create a analyzer surface:
		analyzerSurface = new AnalyzerSurface(this,this);
		analyzerSurface.setVerticalScrollEnabled(preferences.getBoolean(getString(R.string.pref_scrollDB), true));
		analyzerSurface.setVerticalZoomEnabled(preferences.getBoolean(getString(R.string.pref_zoomDB), true));
		analyzerSurface.setWaterfallColorMapType(Integer.valueOf(preferences.getString(getString(R.string.pref_colorMapType),"4")));
		analyzerSurface.setFftDrawingType(Integer.valueOf(preferences.getString(getString(R.string.pref_fftDrawingType),"2")));
		analyzerSurface.setFftRatio(Float.valueOf(preferences.getString(getString(R.string.pref_spectrumWaterfallRatio), "0.5")));
		analyzerSurface.setFontSize(Integer.valueOf(preferences.getString(getString(R.string.pref_fontSize),"2")));

		// Put the analyzer surface in the analyzer frame of the layout:
		fl_analyzerFrame.addView(analyzerSurface);

		// Restore / Initialize the running state and the demodulator mode:
		if(savedInstanceState != null) {
			running = savedInstanceState.getBoolean(getString(R.string.save_state_running));
			demodulationMode = savedInstanceState.getInt(getString(R.string.save_state_demodulatorMode));
		} else {
			// Set running to true if autostart is enabled (this will start the analyzer in onStart() )
			running = preferences.getBoolean((getString(R.string.pref_autostart)), false);
		}

		// Set the hardware volume keys to work on the music audio stream:
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if(source != null && source.isOpen())
			source.close();
		if(logcat != null) {
			try {
				logcat.destroy();
				logcat.waitFor();
				Log.i(LOGTAG, "onDestroy: logcat exit value: " + logcat.exitValue());
			} catch (Exception e) {
				Log.e(LOGTAG, "onDestroy: couldn't stop logcat: " + e.getMessage());
			}
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putBoolean(getString(R.string.save_state_running), running);
		outState.putInt(getString(R.string.save_state_demodulatorMode), demodulationMode);
		if(analyzerSurface != null) {
			outState.putLong(getString(R.string.save_state_channelFrequency), analyzerSurface.getChannelFrequency());
			outState.putInt(getString(R.string.save_state_channelWidth), analyzerSurface.getChannelWidth());
			outState.putFloat(getString(R.string.save_state_squelch), analyzerSurface.getSquelch());
			outState.putLong(getString(R.string.save_state_virtualFrequency), analyzerSurface.getVirtualFrequency());
			outState.putInt(getString(R.string.save_state_virtualSampleRate), analyzerSurface.getVirtualSampleRate());
			outState.putFloat(getString(R.string.save_state_minDB), analyzerSurface.getMinDB());
			outState.putFloat(getString(R.string.save_state_maxDB), analyzerSurface.getMaxDB());
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		// Get a reference to the start-stop button:
		mi_startStop = menu.findItem(R.id.action_startStop);
		mi_demodulationMode = menu.findItem(R.id.action_setDemodulation);

		// update the action bar icons and titles according to the app state:
		updateActionBar();
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		switch (id) {
			case R.id.action_startStop:		if(running)
												stopAnalyzer();
											else
												startAnalyzer();
											break;
			case R.id.action_setDemodulation: showDemodulationDialog();
											break;
			case R.id.action_setFrequency:	tuneToFrequency();
											break;
			case R.id.action_setGain:		adjustGain();
											break;
			case R.id.action_autoscale:		analyzerSurface.autoscale();
											break;
			case R.id.action_settings:		Intent intentShowSettings = new Intent(getApplicationContext(), SettingsActivity.class);
											startActivity(intentShowSettings);
											break;
			case R.id.action_help:			Intent intentShowHelp = new Intent(Intent.ACTION_VIEW);
											intentShowHelp.setData(Uri.parse(getString(R.string.help_url)));
											startActivity(intentShowHelp);
											break;
			default:
		}
		return true;
	}

	/**
	 * Will update the action bar icons and titles according to the current app state
	 */
	private void updateActionBar() {
		// Set title and icon of the start/stop button according to the state:
		if(mi_startStop != null) {
			if (running) {
				mi_startStop.setTitle(R.string.action_stop);
				mi_startStop.setIcon(R.drawable.ic_action_pause);
			} else {
				mi_startStop.setTitle(R.string.action_start);
				mi_startStop.setIcon(R.drawable.ic_action_play);
			}
		}

		// Set title and icon for the demodulator mode button
		if(mi_demodulationMode != null) {
			int iconRes;
			int titleRes;
			switch (demodulationMode) {
				case Demodulator.DEMODULATION_OFF:
					iconRes = R.drawable.ic_action_demod_off;
					titleRes = R.string.action_demodulation_off;
					break;
				case Demodulator.DEMODULATION_AM:
					iconRes = R.drawable.ic_action_demod_am;
					titleRes = R.string.action_demodulation_am;
					break;
				case Demodulator.DEMODULATION_NFM:
					iconRes = R.drawable.ic_action_demod_nfm;
					titleRes = R.string.action_demodulation_nfm;
					break;
				case Demodulator.DEMODULATION_WFM:
					iconRes = R.drawable.ic_action_demod_wfm;
					titleRes = R.string.action_demodulation_wfm;
					break;
				default:
					Log.e(LOGTAG,"updateActionBar: invalid mode: " + demodulationMode);
					iconRes = -1;
					titleRes = -1;
					break;
			}
			if(titleRes > 0 && iconRes > 0) {
				mi_demodulationMode.setTitle(titleRes);
				mi_demodulationMode.setIcon(iconRes);
			}
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		// Check if the user changed the preferences:
		checkForChangedPreferences();

		// Start the analyzer if running is true:
		if (running)
			startAnalyzer();

		// on the first time after the app was killed by the system, savedInstanceState will be
		// non-null and we restore the settings:
		if(savedInstanceState != null) {
			analyzerSurface.setVirtualFrequency(savedInstanceState.getLong(getString(R.string.save_state_virtualFrequency)));
			analyzerSurface.setVirtualSampleRate(savedInstanceState.getInt(getString(R.string.save_state_virtualSampleRate)));
			analyzerSurface.setDBScale(savedInstanceState.getFloat(getString(R.string.save_state_minDB)),
					savedInstanceState.getFloat(getString(R.string.save_state_maxDB)));
			analyzerSurface.setChannelFrequency(savedInstanceState.getLong(getString(R.string.save_state_channelFrequency)));
			analyzerSurface.setChannelWidth(savedInstanceState.getInt(getString(R.string.save_state_channelWidth)));
			analyzerSurface.setSquelch(savedInstanceState.getFloat(getString(R.string.save_state_squelch)));
			if(demodulator != null && scheduler != null) {
				demodulator.setChannelWidth(savedInstanceState.getInt(getString(R.string.save_state_channelWidth)));
				scheduler.setChannelFrequency(savedInstanceState.getLong(getString(R.string.save_state_channelFrequency)));
			}
			savedInstanceState = null; // not needed any more...
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		boolean runningSaved = running;	// save the running state, to restore it after the app re-starts...
		stopAnalyzer();					// will stop the processing loop, scheduler and source
		running = runningSaved;			// running will be saved in onSaveInstanceState()

		// safe preferences:
		if(source != null) {
			SharedPreferences.Editor edit = preferences.edit();
			edit.putLong(getString(R.string.pref_frequency), source.getFrequency());
			edit.putInt(getString(R.string.pref_sampleRate), source.getSampleRate());
			edit.commit();
		}
	}

	@Override
	public void onIQSourceReady(IQSourceInterface source) {	// is called after source.open()
		if (running)
			startAnalyzer();    // will start the processing loop, scheduler and source
	}

	@Override
	public void onIQSourceError(final IQSourceInterface source, final String message) {
		this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(MainActivity.this, "Error with Source [" + source.getName() + "]: " + message, Toast.LENGTH_LONG).show();
				stopAnalyzer();
			}
		});
	}

	/**
	 * Will check if any preference conflicts with the current state of the app and fix it
	 */
	public void checkForChangedPreferences() {
		// Source Type (this is pretty complex as we have to check each type individually):
		int sourceType = Integer.valueOf(preferences.getString(getString(R.string.pref_sourceType), "1"));
		if(source != null) {
			switch (sourceType) {
				case FILE_SOURCE:
					if(!(source instanceof FileIQSource))
						createSource();

					long freq = Integer.valueOf(preferences.getString(getString(R.string.pref_filesource_frequency), "97000000"));
					int sampRate = Integer.valueOf(preferences.getString(getString(R.string.pref_filesource_sampleRate), "2000000"));
					String fileName = preferences.getString(getString(R.string.pref_filesource_file), "");
					boolean repeat = preferences.getBoolean(getString(R.string.pref_filesource_repeat), false);
					if(freq != source.getFrequency() || sampRate != source.getSampleRate()
							|| !fileName.equals(((FileIQSource) source).getFilename())
							|| repeat != ((FileIQSource) source).isRepeat()) {
						createSource();
					}
					break;
				case HACKRF_SOURCE:
					if(!(source instanceof HackrfSource))
						createSource();
					break;
				case RTLSDR_SOURCE:
					break;
				default:
			}
		}

		if(analyzerSurface != null) {
			// All GUI settings will just be overwritten:
			analyzerSurface.setVerticalScrollEnabled(preferences.getBoolean(getString(R.string.pref_scrollDB), true));
			analyzerSurface.setVerticalZoomEnabled(preferences.getBoolean(getString(R.string.pref_zoomDB), true));
			analyzerSurface.setWaterfallColorMapType(Integer.valueOf(preferences.getString(getString(R.string.pref_colorMapType),"4")));
			analyzerSurface.setFftDrawingType(Integer.valueOf(preferences.getString(getString(R.string.pref_fftDrawingType),"2")));
			analyzerSurface.setAverageLength(Integer.valueOf(preferences.getString(getString(R.string.pref_averaging),"0")));
			analyzerSurface.setPeakHoldEnabled(preferences.getBoolean(getString(R.string.pref_peakHold), false));
			analyzerSurface.setFftRatio(Float.valueOf(preferences.getString(getString(R.string.pref_spectrumWaterfallRatio), "0.5")));
			analyzerSurface.setFontSize(Integer.valueOf(preferences.getString(getString(R.string.pref_fontSize),"2")));
		}

		// Screen Orientation:
		String screenOrientation = preferences.getString(getString(R.string.pref_screenOrientation), "auto");
		if(screenOrientation.equals("auto"))
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
		else if(screenOrientation.equals("landscape"))
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		else if(screenOrientation.equals("portrait"))
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		else if(screenOrientation.equals("reverse_landscape"))
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
		else if(screenOrientation.equals("reverse_portrait"))
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
	}

	/**
	 * Will create a IQ Source instance according to the user settings.
	 *
	 * @return true on success; false on error
	 */
	public boolean createSource() {
		int sourceType = Integer.valueOf(preferences.getString(getString(R.string.pref_sourceType), "1"));

		switch (sourceType) {
			case FILE_SOURCE:
						// Create IQ Source (filesource)
						long frequency;
						int sampleRate;
						try {
							frequency = Integer.valueOf(preferences.getString(getString(R.string.pref_filesource_frequency), "97000000"));
							sampleRate = Integer.valueOf(preferences.getString(getString(R.string.pref_filesource_sampleRate), "2000000"));
						} catch (NumberFormatException e) {
							this.runOnUiThread(new Runnable() {
								@Override
								public void run() {
									Toast.makeText(MainActivity.this, "File Source: Wrong format of frequency or sample rate", Toast.LENGTH_LONG).show();
								}
							});
							return false;
						}
						String filename = preferences.getString(getString(R.string.pref_filesource_file), "");
						boolean repeat = preferences.getBoolean(getString(R.string.pref_filesource_repeat), false);
						source = new FileIQSource(filename, sampleRate, frequency, 16384, repeat);
						break;
			case HACKRF_SOURCE:
						// Create HackrfSource
						source = new HackrfSource();
						source.setFrequency(preferences.getLong(getString(R.string.pref_frequency),97000000));
						source.setSampleRate(preferences.getInt(getString(R.string.pref_sampleRate), HackrfSource.MAX_SAMPLERATE));
						((HackrfSource) source).setVgaRxGain(preferences.getInt(getString(R.string.pref_hackrf_vgaRxGain), HackrfSource.MAX_VGA_RX_GAIN/2));
						((HackrfSource) source).setLnaGain(preferences.getInt(getString(R.string.pref_hackrf_lnaGain), HackrfSource.MAX_LNA_GAIN/2));
						break;
			case RTLSDR_SOURCE:
						Log.e(LOGTAG, "createSource: RTLSDR is not implemented!");
						Toast.makeText(this, "RTL-SDR is not implemented!", Toast.LENGTH_LONG).show();
						return false;
			default:	Log.e(LOGTAG, "createSource: Invalid source type: " + sourceType);
						return false;
		}

		// inform the analyzer surface about the new source
		analyzerSurface.setSource(source);

		return true;
	}

	/**
	 * Will stop the RF Analyzer. This includes shutting down the scheduler (which turns of the
	 * source), the processing loop and the demodulator if running.
	 */
	public void stopAnalyzer() {
		// Stop the Scheduler if running:
		if(scheduler != null)
			scheduler.stopScheduler();

		// Stop the Processing Loop if running:
		if(analyzerProcessingLoop != null)
			analyzerProcessingLoop.stopLoop();

		// Stop the Demodulator if running:
		if(demodulator != null)
			demodulator.stopDemodulator();

		// Wait for the scheduler to stop:
		if(scheduler != null) {
			try {
				scheduler.join();
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "startAnalyzer: Error while stopping Scheduler.");
			}
		}

		// Wait for the processing loop to stop
		if(analyzerProcessingLoop != null) {
			try {
				analyzerProcessingLoop.join();
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "startAnalyzer: Error while stopping Processing Loop.");
			}
		}

		// Wait for the demodulator to stop
		if(demodulator != null) {
			try {
				demodulator.join();
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "startAnalyzer: Error while stopping Demodulator.");
			}
		}

		running = false;

		// update action bar icons and titles:
		updateActionBar();

		// allow screen to turn off again:
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}

	/**
	 * Will start the RF Analyzer. This includes creating a source (if null), open a source
	 * (if not open), starting the scheduler (which starts the source) and starting the
	 * processing loop.
	 */
	public void startAnalyzer() {
		this.stopAnalyzer();	// Stop if running; This assures that we don't end up with multiple instances of the thread loops

		// Retrieve fft size and frame rate from the preferences
		int fftSize = Integer.valueOf(preferences.getString(getString(R.string.pref_fftSize), "1024"));
		int frameRate = Integer.valueOf(preferences.getString(getString(R.string.pref_frameRate), "1"));
		boolean dynamicFrameRate = preferences.getBoolean(getString(R.string.pref_dynamicFrameRate), true);

		running = true;

		if(source == null) {
			if(!this.createSource())
				return;
		}

		// check if the source is open. if not, open it!
		if(!source.isOpen()) {
			if (!source.open(this, this)) {
				Toast.makeText(MainActivity.this, "Source not available", Toast.LENGTH_LONG).show();
				running = false;
				return;
			}
			return;	// we have to wait for the source to become ready... onIQSourceReady() will call startAnalyzer() again...
		}

		// Create a new instance of Scheduler and Processing Loop:
		scheduler = new Scheduler(fftSize, source);
		analyzerProcessingLoop = new AnalyzerProcessingLoop(
				analyzerSurface, 			// Reference to the Analyzer Surface
				fftSize,					// FFT size
				scheduler.getFftOutputQueue(), // Reference to the input queue for the processing loop
				scheduler.getFftInputQueue()); // Reference to the buffer-pool-return queue
		if(dynamicFrameRate)
			analyzerProcessingLoop.setDynamicFrameRate(true);
		else {
			analyzerProcessingLoop.setDynamicFrameRate(false);
			analyzerProcessingLoop.setFrameRate(frameRate);
		}

		// Start both threads:
		scheduler.start();
		analyzerProcessingLoop.start();

		scheduler.setChannelFrequency(analyzerSurface.getChannelFrequency());

		// Start the demodulator thread:
		demodulator = new Demodulator(scheduler.getDemodOutputQueue(), scheduler.getDemodInputQueue(), source.getPacketSize());
		demodulator.start();

		// Set the demodulation mode (will configure the demodulator correctly)
		this.setDemodulationMode(demodulationMode);

		// update the action bar icons and titles:
		updateActionBar();

		// Prevent the screen from turning off:
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}

	/**
	 * Will pop up a dialog to let the user choose a demodulation mode.
	 */
	private void showDemodulationDialog() {
		if(scheduler == null || demodulator == null || source == null) {
			Toast.makeText(MainActivity.this, "FFT must be running to change modulation mode", Toast.LENGTH_LONG).show();
			return;
		}

		new AlertDialog.Builder(this)
				.setTitle("Select a demodulation mode:")
				.setSingleChoiceItems(R.array.demodulation_modes, demodulator.getDemodulationMode(), new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						setDemodulationMode(which);
						dialog.dismiss();
					}
				})
				.show();
	}

	/**
	 * Will set the modulation mode to the given value. Takes care of adjusting the
	 * scheduler and the demodulator respectively and updates the action bar menu item.
	 *
	 * @param mode	Demodulator.DEMODULATION_OFF, *_AM, *_NFM, *_WFM
	 */
	public void setDemodulationMode(int mode) {
		if(scheduler == null || demodulator == null || source == null) {
			Log.e(LOGTAG,"setDemodulationMode: scheduler/demodulator/source is null");
			return;
		}

		// (de-)activate demodulation in the scheduler and set the sample rate accordingly:
		if(mode == Demodulator.DEMODULATION_OFF) {
			scheduler.setDemodulationActivated(false);
		}
		else {
			// adjust sample rate of the source:
			source.setSampleRate(Demodulator.INPUT_RATE);

			// Verify that the source supports the sample rate:
			if(source.getSampleRate() != Demodulator.INPUT_RATE) {
				Log.e(LOGTAG,"setDemodulationMode: cannot adjust source sample rate!");
				Toast.makeText(MainActivity.this, "Source does not support the sample rate necessary for demodulation (" +
						Demodulator.INPUT_RATE/1000000 + " Msps)", Toast.LENGTH_LONG).show();
				scheduler.setDemodulationActivated(false);
				mode = Demodulator.DEMODULATION_OFF;	// deactivate demodulation...
			} else {
				scheduler.setDemodulationActivated(true);
			}
		}

		// set demodulation mode in demodulator:
		demodulator.setDemodulationMode(mode);
		this.demodulationMode = mode;	// save the setting

		// disable/enable demodulation view in surface:
		if(mode == Demodulator.DEMODULATION_OFF) {
			analyzerSurface.setDemodulationEnabled(false);
		} else {
			analyzerSurface.setDemodulationEnabled(true);	// will re-adjust channel freq, width and squelch,
															// if they are outside the current viewport and update the
															// demodulator via callbacks.
		}

		// update action bar:
		updateActionBar();
	}

	/**
	 * Will pop up a dialog to let the user input a new frequency.
	 * Note: A frequency can be entered either in Hz or in MHz. If the input value
	 * is a number smaller than the maximum frequency of the source in MHz, then it
	 * is interpreted as a frequency in MHz. Otherwise it will be handled as frequency
	 * in Hz.
	 */
	private void tuneToFrequency() {
		if(source == null)
			return;

		// calculate max frequency of the source in MHz:
		final double maxFreqMHz = source.getMaxFrequency() / 1000000f;

		final EditText et_input = new EditText(this);
		et_input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
		new AlertDialog.Builder(this)
			.setTitle("Tune to Frequency")
			.setMessage("Frequency is " + source.getFrequency()/1000000f + "Hz. Type a new Frequency (Values below "
					+ maxFreqMHz + " will be interpreted as MHz, higher values as Hz): ")
			.setView(et_input)
			.setPositiveButton("Set", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					try {
						double newFreq = Double.valueOf(et_input.getText().toString());
						if (newFreq < maxFreqMHz)
							newFreq = newFreq * 1000000;
						if (newFreq <= source.getMaxFrequency() && newFreq >= source.getMinFrequency()) {
							source.setFrequency((long)newFreq);
							analyzerSurface.setVirtualFrequency((long)newFreq);
							if(demodulationMode != Demodulator.DEMODULATION_OFF)
								analyzerSurface.setDemodulationEnabled(true);	// This will re-adjust the channel freq correctly
						} else {
							Toast.makeText(MainActivity.this, "Frequency is out of the valid range: " + (long)newFreq + " Hz", Toast.LENGTH_LONG).show();
						}
					} catch (NumberFormatException e) {
						Log.e(LOGTAG, "tuneToFrequency: Error while setting frequency: " + e.getMessage());
					}
				}
			})
			.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					// do nothing
				}
			})
			.show();
	}

	/**
	 * Will pop up a dialog to let the user adjust gain settings
	 */
	private void adjustGain() {
		if(source == null)
			return;

		int sourceType = Integer.valueOf(preferences.getString(getString(R.string.pref_sourceType), "1"));
		switch (sourceType) {
			case FILE_SOURCE:
				Toast.makeText(this, getString(R.string.filesource_doesnt_support_gain), Toast.LENGTH_LONG).show();
				break;
			case HACKRF_SOURCE:
				// Prepare layout:
				final LinearLayout view = (LinearLayout) this.getLayoutInflater().inflate(R.layout.hackrf_gain, null);
				final SeekBar sb_vga = (SeekBar) view.findViewById(R.id.sb_hackrf_vga_gain);
				final SeekBar sb_lna = (SeekBar) view.findViewById(R.id.sb_hackrf_lna_gain);
				final TextView tv_vga = (TextView) view.findViewById(R.id.tv_hackrf_vga_gain);
				final TextView tv_lna = (TextView) view.findViewById(R.id.tv_hackrf_lna_gain);
				sb_vga.setMax(HackrfSource.MAX_VGA_RX_GAIN);
				sb_lna.setMax(HackrfSource.MAX_LNA_GAIN);
				sb_vga.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
						tv_vga.setText("" + progress);
					}
					@Override
					public void onStartTrackingTouch(SeekBar seekBar) {
					}
					@Override
					public void onStopTrackingTouch(SeekBar seekBar) {
					}
				});
				sb_lna.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
						tv_lna.setText("" + progress);
					}
					@Override
					public void onStartTrackingTouch(SeekBar seekBar) {
					}
					@Override
					public void onStopTrackingTouch(SeekBar seekBar) {
					}
				});
				sb_vga.setProgress(((HackrfSource)source).getVgaRxGain());
				sb_lna.setProgress(((HackrfSource)source).getLnaGain());

				// Show dialog:
				new AlertDialog.Builder(this)
						.setTitle("Adjust Gain Settings")
						.setView(view)
						.setPositiveButton("Set", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int whichButton) {
								((HackrfSource)source).setVgaRxGain(sb_vga.getProgress());
								((HackrfSource)source).setLnaGain(sb_lna.getProgress());
								// safe preferences:
								SharedPreferences.Editor edit = preferences.edit();
								edit.putInt(getString(R.string.pref_hackrf_vgaRxGain), sb_vga.getProgress());
								edit.putInt(getString(R.string.pref_hackrf_lnaGain), sb_lna.getProgress());
								edit.apply();
							}
						})
						.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int whichButton) {
								// do nothing
							}
						})
						.show()
						.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
				break;
			case RTLSDR_SOURCE:
				Log.e(LOGTAG, "adjustGain: RTLSDR is not implemented!");
				Toast.makeText(this, "RTL-SDR is not implemented!", Toast.LENGTH_LONG).show();
				break;
			default:
				Log.e(LOGTAG, "adjustGain: Invalid source type: " + sourceType);
				break;
		}
	}

	/**
	 * Called by the analyzer surface after the user changed the channel width
	 * @param newChannelWidth    new channel width (single sided) in Hz
	 * @return true if channel width is valid; false if out of range
	 */
	@Override
	public boolean onUpdateChannelWidth(int newChannelWidth) {
		return demodulator.setChannelWidth(newChannelWidth);
	}

	@Override
	public void onUpdateChannelFrequency(long newChannelFrequency) {
		scheduler.setChannelFrequency(newChannelFrequency);
	}

	@Override
	public void onUpdateSquelchSatisfied(boolean squelchSatisfied) {
		scheduler.setSquelchSatisfied(squelchSatisfied);
	}

	@Override
	public int onCurrentChannelWidthRequested() {
		if(demodulator != null)
			return demodulator.getChannelWidth();
		else
			return -1;
	}
}
