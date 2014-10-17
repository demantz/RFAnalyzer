package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
public class MainActivity extends Activity implements IQSourceInterface.Callback {

	private FrameLayout fl_analyzerFrame = null;
	private AnalyzerSurface analyzerSurface = null;
	private AnalyzerProcessingLoop analyzerProcessingLoop = null;
	private IQSourceInterface source = null;
	private Scheduler scheduler = null;
	SharedPreferences preferences = null;
	private boolean running = false;

	private static final String LOGTAG = "MainActivity";
	private static final int FILE_SOURCE = 0;
	private static final int HACKRF_SOURCE = 1;
	private static final int RTLSDR_SOURCE = 2;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Get references to the GUI components:
		fl_analyzerFrame = (FrameLayout) findViewById(R.id.fl_analyzerFrame);

		// Get reference to the shared preferences:
		preferences = getPreferences(Context.MODE_PRIVATE);

		// Create a analyzer surface:
		analyzerSurface = new AnalyzerSurface(this);

		// Put the analyzer surface in the analyzer frame of the layout:
		fl_analyzerFrame.addView(analyzerSurface);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if(source != null && source.isOpen())
			source.close();
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putBoolean(getString(R.string.save_state_running), running);
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		running = savedInstanceState.getBoolean(getString(R.string.save_state_running));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		switch (id) {
			case R.id.action_start:			startAnalyzer();
											break;
			case R.id.action_stop:			stopAnalyzer();
											break;
			case R.id.action_setFrequency:	tuneToFrequency();
											break;
			case R.id.action_setGain:		adjustGain();
											break;
			case R.id.action_autoscale:		//analyzerSurface.autoscale();
											break;
			case R.id.action_settings:
			default:
		}
		return true;
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (running) {
			startAnalyzer();
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		boolean runningSaved = running;	// save the running state, to restore it after the app re-starts...
		stopAnalyzer();					// will stop the processing loop, scheduler and source
		running = runningSaved;			// running will be saved in onSaveInstanceState()
	}

	@Override
	public void onIQSourceReady(IQSourceInterface source) {	// is called after source.open()
		if (running)
			startAnalyzer();	// will start the processing loop, scheduler and source
	}

	@Override
	public void onIQSourceError(final IQSourceInterface source, final String message) {
		this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
			Toast.makeText(MainActivity.this, "Error with Source [" + source.getName() + "]: " + message, Toast.LENGTH_LONG).show();
			}
		});
		stopAnalyzer();
	}

	/**
	 * Will create a IQ Source instance according to the user settings. May pop up dialogs to
	 * let the user choose.
	 *
	 * @return true on success; false on error
	 */
	public boolean createSource() {
		int sourceType = preferences.getInt(getString(R.string.prefs_sourceType),1);

		switch (sourceType) {
			case FILE_SOURCE:
						// Create IQ Source (filesource)
						File file = new File(Environment.getExternalStorageDirectory() + "/Test_HackRF", "hackrf_android.iq");
						source = new FileIQSource(file, 2000000, 931000000, 16384, true);
						return true;
			case HACKRF_SOURCE:
						// Create HackrfSource
						source = new HackrfSource();
						source.setFrequency(preferences.getLong(getString(R.string.prefs_frequency),97000000));
						source.setSampleRate(preferences.getInt(getString(R.string.prefs_sampleRate), HackrfSource.MAX_SAMPLERATE));
						((HackrfSource) source).setVgaRxGain(preferences.getInt(getString(R.string.prefs_hackrf_vgaRxGain), HackrfSource.MAX_VGA_RX_GAIN/2));
						((HackrfSource) source).setLnaGain(preferences.getInt(getString(R.string.prefs_hackrf_lnaGain), HackrfSource.MAX_LNA_GAIN/2));
						return true;
			case RTLSDR_SOURCE:
						Log.e(LOGTAG, "createSource: RTLSDR is not implemented!");
						Toast.makeText(this, "RTL-SDR is not implemented!", Toast.LENGTH_LONG).show();
			default:	Log.e(LOGTAG, "createSource: Invalid source type: " + sourceType);
						return false;
		}
	}

	/**
	 * Will stop the RF Analyzer. This includes shutting down the scheduler (which turns of the
	 * source) and the processing loop.
	 */
	public void stopAnalyzer() {
		// Stop the Scheduler if running:
		if(scheduler != null)
			scheduler.stopScheduler();

		// Stop the Processing Loop if running:
		if(analyzerProcessingLoop != null)
			analyzerProcessingLoop.stopLoop();

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

		running = false;
	}

	/**
	 * Will start the RF Analyzer. This includes creating a source (if null), open a source
	 * (if not open), starting the scheduler (which starts the source) and starting the
	 * processing loop.
	 */
	public void startAnalyzer() {
		this.stopAnalyzer();	// Stop if running; This assures that we don't end up with multiple instances of the thread loops

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

		analyzerSurface.setSource(source);

		// Create a new instance of Scheduler and Processing Loop:
		int fftSize = 1024;
		scheduler = new Scheduler(fftSize, source);
		analyzerProcessingLoop = new AnalyzerProcessingLoop(
				analyzerSurface, 			// Reference to the Analyzer Surface
				fftSize,					// FFT size
				scheduler.getOutputQueue(), // Reference to the input queue for the processing loop
				scheduler.getInputQueue()); // Reference to the buffer-pool-return queue
		analyzerProcessingLoop.setFrameRate(10);

		// Start both threads:
		scheduler.start();
		analyzerProcessingLoop.start();
	}

	/**
	 * Will pop up a dialog to let the user input a new frequency.
	 */
	private void tuneToFrequency() {
		if(source == null)
			return;

		final EditText et_input = new EditText(this);
		et_input.setInputType(InputType.TYPE_CLASS_NUMBER);
		new AlertDialog.Builder(this)
			.setTitle("Tune to Frequency")
			.setMessage("Frequency is " + source.getFrequency() + "Hz. Type new Frequency in Hz: ")
			.setView(et_input)
			.setPositiveButton("Set", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					try {
						long newFreq = Long.valueOf(et_input.getText().toString());
						source.setFrequency(newFreq);
						analyzerSurface.centerAroundFrequency(newFreq);
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

		int sourceType = preferences.getInt(getString(R.string.prefs_sourceType),-1);
		switch (sourceType) {
			case HACKRF_SOURCE:
				final LinearLayout view = null;//this.getLayoutInflater().inflate(R.layout.hackrf_adjust_gain);
				new AlertDialog.Builder(this)
						.setTitle("Adjust Gain Settings")
						.setView(view)
						.setPositiveButton("Set", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int whichButton) {
								//todo
							}
						})
						.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int whichButton) {
								// do nothing
							}
						})
						.show();
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
}
