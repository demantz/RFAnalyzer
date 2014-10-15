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
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;


public class MainActivity extends Activity implements IQSourceInterface.Callback {

	private FrameLayout fl_analyzerFrame = null;
	private AnalyzerSurface analyzerSurface = null;
	private AnalyzerProcessingLoop analyzerProcessingLoop = null;
	private IQSourceInterface source = null;
	private Scheduler scheduler = null;
	SharedPreferences preferences = null;
	private static final String logtag = "MainActivity";
	private boolean running = false;

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
		analyzerSurface.setFrequency(preferences.getLong(getString(R.string.prefs_frequency),97000000));
		analyzerSurface.setSampleRate(preferences.getInt(getString(R.string.prefs_sampleRate), 20000000));

		// Put the analyzer surface in the analyzer frame of the layout:
		fl_analyzerFrame.addView(analyzerSurface);

		// Create the IQ Source
		createSource();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if(source.isOpen())
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
			case R.id.action_start:			startReceiving();
											break;
			case R.id.action_stop:			stopAnalyzer();
											break;
			case R.id.action_setFrequency:	tuneToFrequency();
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
			startReceiving();
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		stopAnalyzer();			// will stop the processing loop, scheduler and source
	}

	@Override
	public void onIQSourceReady(IQSourceInterface source) {
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
		running = false;
	}

	public void createSource() {
		// todo create the source according to user settings
		int sourceType = 1;
		switch (sourceType) {
			case 0:
						// Create IQ Source (filesource)
						File file = new File(Environment.getExternalStorageDirectory() + "/Test_HackRF", "hackrf_android.io");
						source = new FileIQSource(file, 2000000, 931000000, 16384, true);
						break;
			case 1:
						// Create HackrfSource
						source = new HackrfSource();
						source.setFrequency(preferences.getLong(getString(R.string.prefs_frequency),97000000));
						source.setSampleRate(preferences.getInt(getString(R.string.prefs_sampleRate), 20000000));
						((HackrfSource) source).setVgaRxGain(preferences.getInt(getString(R.string.prefs_hackrf_vgaRxGain), 10));
						((HackrfSource) source).setLnaGain(preferences.getInt(getString(R.string.prefs_hackrf_lnaGain), 40));
						break;
			default:
		}

		// open the source:
		if(!source.open(this, this)) {
			Log.w(logtag, "openSource: Couldn't open source (maybe not available)");
		}
		// will call onIQSourceReady...
	}

	public void startReceiving() {
		running = true;
		if(source.isOpen())
			startAnalyzer();	// will start the processing loop, scheduler and source
		else {
			if (!source.open(this, this)) {
				Toast.makeText(MainActivity.this, "Source not available", Toast.LENGTH_LONG).show();
				running = false;
			}
		}
	}

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
				Log.e(logtag, "startAnalyzer: Error while stopping Scheduler.");
			}
		}

		// Wait for the processing loop to stop
		if(analyzerProcessingLoop != null) {
			try {
				analyzerProcessingLoop.join();
			} catch (InterruptedException e) {
				Log.e(logtag, "startAnalyzer: Error while stopping Processing Loop.");
			}
		}
	}

	public void startAnalyzer() {
		this.stopAnalyzer();	// Stop if running

		// Create a new instance of Scheduler and Processing Loop:
		int fftSize = 1024;
		int frameRate = 5;
		scheduler = new Scheduler(frameRate, fftSize, source);
		analyzerProcessingLoop = new AnalyzerProcessingLoop(
				analyzerSurface, 			// Reference to the Analyzer Surface
				frameRate,					// Frame Rate
				scheduler.getOutputQueue(), // Reference to the input queue for the processing loop
				scheduler.getInputQueue()); // Reference to the buffer-pool-return queue
		analyzerProcessingLoop.setFftSize(fftSize);

		// Start both threads:
		scheduler.start();
		analyzerProcessingLoop.start();
	}


	private void tuneToFrequency() {
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
						analyzerSurface.setFrequency(newFreq);
					} catch (NumberFormatException e) {
						Log.e(logtag, "tuneToFrequency: Error while setting frequency: " + e.getMessage());
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
}
