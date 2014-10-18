package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
public class MainActivity extends Activity implements IQSourceInterface.Callback  {

	private FrameLayout fl_analyzerFrame = null;
	private AnalyzerSurface analyzerSurface = null;
	private AnalyzerProcessingLoop analyzerProcessingLoop = null;
	private IQSourceInterface source = null;
	private Scheduler scheduler = null;
	private SharedPreferences preferences = null;
	private Bundle savedInstanceState = null;
	private boolean running = false;

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

		// Get references to the GUI components:
		fl_analyzerFrame = (FrameLayout) findViewById(R.id.fl_analyzerFrame);

		// Get reference to the shared preferences:
		preferences = PreferenceManager.getDefaultSharedPreferences(this);

		// Create a analyzer surface:
		analyzerSurface = new AnalyzerSurface(this);
		analyzerSurface.setVerticalScrollEnabled(preferences.getBoolean(getString(R.string.pref_scrollDB), true));
		analyzerSurface.setVerticalZoomEnabled(preferences.getBoolean(getString(R.string.pref_zoomDB), true));

		// Put the analyzer surface in the analyzer frame of the layout:
		fl_analyzerFrame.addView(analyzerSurface);

		// Restore / Initialize the running state:
		if(savedInstanceState != null) {
			running = savedInstanceState.getBoolean(getString(R.string.save_state_running));
		} else {
			// Set running to true if autostart is enabled (this will start the analyzer in onStart() )
			running = preferences.getBoolean((getString(R.string.pref_autostart)), false);
		}
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
		if(analyzerSurface != null) {
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

	@Override
	protected void onStart() {
		super.onStart();
		// Check if the user changed the preferences:
		checkForChangedPreferences();

		// Start the analyzer if running is true:
		if (running)
			startAnalyzer();
	}

	@Override
	protected void onStop() {
		super.onStop();
		boolean runningSaved = running;	// save the running state, to restore it after the app re-starts...
		stopAnalyzer();					// will stop the processing loop, scheduler and source
		running = runningSaved;			// running will be saved in onSaveInstanceState()

		// safe preferences:
		SharedPreferences.Editor edit = preferences.edit();
		edit.putLong(getString(R.string.pref_frequency), source.getFrequency());
		edit.putInt(getString(R.string.pref_sampleRate), source.getSampleRate());
		edit.commit();
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

		// All GUI settings will just be overwritten:
		if(analyzerSurface != null) {
			analyzerSurface.setVerticalScrollEnabled(preferences.getBoolean(getString(R.string.pref_scrollDB), true));
			analyzerSurface.setVerticalZoomEnabled(preferences.getBoolean(getString(R.string.pref_zoomDB), true));
		}
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
		// on the first time after the app was killed by the system, savedInstanceState will be
		// non-null and we restore the settings:
		if(savedInstanceState != null) {
			analyzerSurface.setVirtualFrequency(savedInstanceState.getLong(getString(R.string.save_state_virtualFrequency)));
			analyzerSurface.setVirtualSampleRate(savedInstanceState.getInt(getString(R.string.save_state_virtualSampleRate)));
			analyzerSurface.setDBScale(savedInstanceState.getFloat(getString(R.string.save_state_minDB)),
					savedInstanceState.getFloat(getString(R.string.save_state_maxDB)));
			savedInstanceState = null; // not needed any more...
		}
		return true;
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
				scheduler.getOutputQueue(), // Reference to the input queue for the processing loop
				scheduler.getInputQueue()); // Reference to the buffer-pool-return queue
		if(dynamicFrameRate)
			analyzerProcessingLoop.setDynamicFrameRate(true);
		else {
			analyzerProcessingLoop.setDynamicFrameRate(false);
			analyzerProcessingLoop.setFrameRate(frameRate);
		}

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
						analyzerSurface.setVirtualFrequency(newFreq);
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
