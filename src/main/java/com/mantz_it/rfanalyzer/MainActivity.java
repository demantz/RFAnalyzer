package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.FrameLayout;

import java.io.File;


public class MainActivity extends Activity {

	private FrameLayout fl_analyzerFrame = null;
	private AnalyzerSurface analyzerSurface = null;
	private AnalyzerProcessingLoop analyzerProcessingLoop = null;
	private IQSourceInterface source = null;
	private Scheduler scheduler = null;

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


		int fftSize = 1024;
		int frameRate = 5;
		// Create IQ Source (filesource)
		File file = new File(Environment.getExternalStorageDirectory() + "/Test_HackRF", "hackrf_android.io");
		source = new FileIQSource(file, 2000000, 16384, true);
		// Create Scheduler (start on resume)
		scheduler = new Scheduler(frameRate, fftSize, source);

		// Create the analyzer loop (start on resume):
		analyzerProcessingLoop = new AnalyzerProcessingLoop(
				analyzerSurface, 			// Reference to the Analyzer Surface
				2000000, 					// Sample Rate
				frameRate,					// Frame Rate
				scheduler.getOutputQueue(), // Reference to the input queue for the processing loop
				scheduler.getInputQueue()); // Reference to the buffer-pool-return queue
		analyzerProcessingLoop.setFftSize(fftSize);
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
		if (id == R.id.action_settings) {
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onResume() {
		super.onResume();
		if(!scheduler.isRunning())
			scheduler.start();
		if(!analyzerProcessingLoop.isRunning())
			analyzerProcessingLoop.start();
	}

	@Override
	protected void onPause() {
		super.onPause();
		scheduler.stopScheduler();
		analyzerProcessingLoop.stopLoop();
	}
}
