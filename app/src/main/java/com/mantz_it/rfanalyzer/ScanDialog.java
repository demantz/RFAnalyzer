package com.mantz_it.rfanalyzer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * <h1>RF Analyzer - Scan Dialog</h1>
 *
 * Module:      ScanDialog.java
 * Description: Popup dialog to start a scan run
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
public class ScanDialog implements DialogInterface.OnClickListener, View.OnClickListener, SeekBar.OnSeekBarChangeListener {
	private Activity activity;
	private AnalyzerProcessingLoop analyzerProcessingLoop;
	private AnalyzerSurface analyzerSurface;
	private RFControlInterface rfControlInterface;
	private String logDir;
	private SimpleDateFormat simpleDateFormat;
	private float squelch;

	private AlertDialog dialog;
	private AlertDialog addRangeDialog;
	private ChannelListAdapter channelListAdapter;

	private ScrollView view;
	private Button bt_addRange;
	private Button bt_addChannel;
	private Button bt_addBookmark;
	private Button bt_clear;
	private ListView lv_channelList;
	private SeekBar sb_squelch;
	private TextView tv_squelch;
	private CheckBox cb_log;
	private CheckBox cb_demodulate;
	private CheckBox cb_stopAfterFirstFound;
	private EditText et_logfile;

	public ScanDialog(Activity activity, AnalyzerProcessingLoop analyzerProcessingLoop,
					  AnalyzerSurface analyzerSurface, RFControlInterface rfControlInterface, String logDir) {
		this.activity = activity;
		this.analyzerProcessingLoop = analyzerProcessingLoop;
		this.analyzerSurface = analyzerSurface;
		this.rfControlInterface = rfControlInterface;
		this.logDir = logDir;

		// Get references to the GUI components:
		view = (ScrollView) activity.getLayoutInflater().inflate(R.layout.start_scanning, null);
		bt_addRange = (Button) view.findViewById(R.id.bt_scanning_addRange);
		bt_addChannel = (Button) view.findViewById(R.id.bt_scanning_addChannel);
		bt_addBookmark = (Button) view.findViewById(R.id.bt_scanning_addBookmark);
		bt_clear = (Button) view.findViewById(R.id.bt_scanning_clear);
		lv_channelList = (ListView) view.findViewById(R.id.lv_scanning_channelList);
		sb_squelch = (SeekBar) view.findViewById(R.id.sb_scanning_squelch);
		tv_squelch = (TextView) view.findViewById(R.id.tv_scanning_squelch);
		cb_log = (CheckBox) view.findViewById(R.id.cb_scanning_log);
		cb_demodulate = (CheckBox) view.findViewById(R.id.cb_scanning_demodulate);
		cb_stopAfterFirstFound = (CheckBox) view.findViewById(R.id.cb_scanning_stopAfterFirstFound);
		et_logfile = (EditText) view.findViewById(R.id.et_scanning_logfile);

		// Set up all GUI components
		bt_addRange.setOnClickListener(this);
		bt_addBookmark.setOnClickListener(this);
		bt_addChannel.setOnClickListener(this);
		bt_clear.setOnClickListener(this);
		channelListAdapter = new ChannelListAdapter(activity);
		// Seekbar from 0 to DB span times 1000:
		sb_squelch.setMax((int)((analyzerSurface.getMaxDB()-analyzerSurface.getMinDB())*1000));
		sb_squelch.setOnSeekBarChangeListener(this);
		squelch = analyzerSurface.getSquelch();
		if(Float.isNaN(squelch) || squelch < analyzerSurface.getMinDB() || squelch > analyzerSurface.getMaxDB())
			squelch = (analyzerSurface.getMinDB() + analyzerSurface.getMaxDB()) / 2f;
		sb_squelch.setProgress((int)((squelch-analyzerSurface.getMinDB())*1000));

		simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
		lv_channelList.setAdapter(channelListAdapter);

		// Prepare dialog elements
		et_logfile.setText(simpleDateFormat.format(new Date()) + "_scan.txt");

		// create and show dialog:
		dialog = new AlertDialog.Builder(activity)
			.setTitle("Scan Spectrum")
			.setView(view)
			.setPositiveButton("Start", this)
			.setNegativeButton("Cancel", this)
			.create();
		dialog.show();
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
	}

	public void bt_addRangeClicked() {
		final LinearLayout addRangeView = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.scanning_add_range, null);
		final EditText et_startFrequency = (EditText) addRangeView.findViewById(R.id.et_scanning_addRange_startFrequency);
		final EditText et_endFrequency = (EditText) addRangeView.findViewById(R.id.et_scanning_addRange_endFrequency);
		final EditText et_channelWidth = (EditText) addRangeView.findViewById(R.id.et_scanning_addRange_channelWidth);
		final Spinner sp_mode = (Spinner) addRangeView.findViewById(R.id.sp_scanning_addRange_mode);

		final ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(activity, R.array.demodulation_modes, android.R.layout.simple_spinner_item);
		modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		sp_mode.setAdapter(modeAdapter);

		et_startFrequency.setText("" + (rfControlInterface.requestCurrentSourceFrequency() - rfControlInterface.requestCurrentSampleRate()/2));
		et_endFrequency.setText("" + (rfControlInterface.requestCurrentSourceFrequency() + rfControlInterface.requestCurrentSampleRate()/2));
		et_channelWidth.setText("100000");
		// Show dialog:
		new AlertDialog.Builder(activity)
				.setTitle("Add Range")
				.setView(addRangeView)
				.setPositiveButton("Add", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						long startFreq = Long.valueOf(et_startFrequency.getText().toString());
						int channelWidth = Integer.valueOf(et_channelWidth.getText().toString());
						int channelCount = (int) (Long.valueOf(et_endFrequency.getText().toString()) - startFreq) / channelWidth;
						int mode = sp_mode.getSelectedItemPosition();
						for (int i = 0; i < channelCount; i++) {
							Channel tmpChannel = new Channel(startFreq + channelWidth/2 + i*channelWidth, channelWidth, 0);
							tmpChannel.setMode(mode);
							channelListAdapter.add(tmpChannel);
						}
					}
				})
				.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						// do nothing
					}
				})
				.show()
				.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
	}

	@Override
	public void onClick(DialogInterface dialog, int which) {
		// "Scan"/"Cancel" was clicked...
		if(which == DialogInterface.BUTTON_POSITIVE) {
			String filename = null;
			boolean stopAfterFirstFound;
			boolean demodulate;
			List<Channel> channelList;

			if (cb_log.isChecked()) {
				filename = logDir + "/" + et_logfile.getText().toString();

				// make sure the app directory exists:
				File app_dir = new File(logDir);
				app_dir.mkdir();    // Create directory; does nothing if directory already exists
			}

			stopAfterFirstFound = cb_stopAfterFirstFound.isChecked();
			demodulate = cb_demodulate.isChecked();
			channelList = new ArrayList<>();
			for (int i = 0; i < channelListAdapter.getCount(); i++) {
				channelList.add(channelListAdapter.getItem(i));
			}

			Scanner scanner;
			if(demodulate)
				scanner = new Scanner(analyzerSurface, rfControlInterface, channelList,
										squelch, stopAfterFirstFound, filename);
			else
				scanner = new Scanner(analyzerSurface, null, channelList, squelch, stopAfterFirstFound, filename);
			analyzerProcessingLoop.setScanner(scanner);
			analyzerSurface.setScanner(scanner);
		}
	}


	@Override
	public void onClick(View v) {
		if(v == bt_addRange)
			bt_addRangeClicked();
		else if(v == bt_clear)
			channelListAdapter.clear();
		//todo other buttons
	}

	@Override
	public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
		// update the textview:
		squelch = analyzerSurface.getMinDB() + progress * 0.001f;
		tv_squelch.setText(new DecimalFormat("  #.## dB").format(squelch));
	}

	@Override
	public void onStartTrackingTouch(SeekBar seekBar) {
		// do nothing
	}

	@Override
	public void onStopTrackingTouch(SeekBar seekBar) {
		// do nothing
	}

	public class ChannelListAdapter extends ArrayAdapter<Channel> {
		public ChannelListAdapter(Context context) {
			super(context, R.layout.channel_list_item);
		}

		public View getView(int position, View convertView, ViewGroup parent) {
			View view = convertView;
			Channel channel = this.getItem(position);

			// first check to see if the view is null and inflate a new one if necessary:
			if (view == null) {
				LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				view = inflater.inflate(R.layout.channel_list_item, null);
			}

			if (channel != null) {
				TextView tv_channelFreq = (TextView) view.findViewById(R.id.tv_channelItem_title);
				TextView tv_channelRange = (TextView) view.findViewById(R.id.tv_channelItem_details);
				tv_channelFreq.setText("" + channel.getFrequency());
				tv_channelRange.setText("" + channel.getStartFrequency() + " - " + channel.getEndFrequency());
			}

			return view;
		}
	}
}
