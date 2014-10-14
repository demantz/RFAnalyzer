package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;


public class MainActivity extends Activity implements IQSourceInterface.Callback {

	private FrameLayout fl_analyzerFrame = null;
	private AnalyzerSurface analyzerSurface = null;
	private AnalyzerProcessingLoop analyzerProcessingLoop = null;
	private IQSourceInterface source = null;
	private Scheduler scheduler = null;
	private static final String logtag = "MainActivity";
	private boolean running = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Get references to the GUI components:
		fl_analyzerFrame = (FrameLayout) findViewById(R.id.fl_analyzerFrame);

		// Create a analyzer surface:
		analyzerSurface = new AnalyzerSurface(this);

		// Put the analyzer surface in the analyzer frame of the layout:
		fl_analyzerFrame.addView(analyzerSurface);

		// Open the IQ Source
		openSource();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if(source != null)
			source.close();
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
			case R.id.action_start:		running = true;
										startAnalyzer();
										break;
			case R.id.action_settings:
			default:
		}
		return true;
	}

	@Override
	protected void onPause() {
		super.onPause();
		stopAnalyzer();			// will stop the processing loop, scheduler and source
	}

	@Override
	protected void onResume() {
		super.onResume();
		if(source != null && running)
			startAnalyzer();	// will start the processing loop, scheduler and source
	}

	@Override
	public void onIQSourceReady(IQSourceInterface source) {
		if (source != null && running)
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
	}

	public void openSource() {
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
						source.setFrequency(97000000);
						source.setSampleRate(2000000);
						((HackrfSource) source).setLnaGain(30);
						((HackrfSource) source).setLnaGain(40);
						break;
			default:
		}

		// open the source:
		if(!source.open(this, this)) {
			Log.e(logtag, "openSource: Error while opening source");
		}
		// will call onIQSourceReady...
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
				source.getSampleRate(),		// Sample Rate
				frameRate,					// Frame Rate
				scheduler.getOutputQueue(), // Reference to the input queue for the processing loop
				scheduler.getInputQueue()); // Reference to the buffer-pool-return queue
		analyzerProcessingLoop.setFftSize(fftSize);
		analyzerProcessingLoop.setBasebandFrequency(source.getFrequency());

		// Start both threads:
		scheduler.start();
		analyzerProcessingLoop.start();
	}


}
