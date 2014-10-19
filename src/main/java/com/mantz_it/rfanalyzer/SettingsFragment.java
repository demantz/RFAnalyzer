package com.mantz_it.rfanalyzer;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import java.io.File;

/**
 * <h1>RF Analyzer - Settings Fragment</h1>
 *
 * Module:      SettingsFragment.java
 * Description: This fragment shows all app settings
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
public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener,
																	Preference.OnPreferenceClickListener {

	private static final int FILESOURCE_RESULT_CODE = 1;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);

		// Add click listener to preferences which use external apps:
		Preference pref = findPreference(getString(R.string.pref_filesource_file));
		pref.setOnPreferenceClickListener(this);
		pref = findPreference(getString(R.string.pref_showLog));
		pref.setOnPreferenceClickListener(this);

	}

	@Override
	public void onResume() {
		super.onResume();
		getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
		updateSummaries();

		// Screen Orientation:
		String screenOrientation = getPreferenceScreen().getSharedPreferences().getString(getString(R.string.pref_screenOrientation), "auto");
		setScreenOrientation(screenOrientation);
	}

	@Override
	public void onPause() {
		super.onPause();
		getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
	}

	@Override
	public boolean onPreferenceClick(Preference preference) {
		// FileSource file:
		if(preference.getKey().equals(getString(R.string.pref_filesource_file))) {
			try {
				Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
				intent.setType("*/*");
				intent.addCategory(Intent.CATEGORY_OPENABLE);
				startActivityForResult(Intent.createChooser(intent, "Select a file (8-bit complex IQ samples)"), FILESOURCE_RESULT_CODE);

				// No error so far... let's dismiss the text input dialog:
				Dialog dialog = ((EditTextPreference)preference).getDialog();
				if(dialog != null)
					dialog.dismiss();
				return true;
			} catch (ActivityNotFoundException e) {
				Toast.makeText(SettingsFragment.this.getActivity(), "No file browser is installed!", Toast.LENGTH_LONG).show();
				// Note that there is still the text dialog visible for the user to input a file path... so no more error handling necessary
			}
			return false;
		}
		// Show Log:
		else if (preference.getKey().equals(getString(R.string.pref_showLog))) {
			try {
				String logfile = ((EditTextPreference) findPreference(getString(R.string.pref_logfile))).getText();
				Uri uri = Uri.fromFile(new File(logfile));
				Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setDataAndType(uri, "text/plain");
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				this.startActivity(intent);
				return true;
			} catch (ActivityNotFoundException e) {
				Toast.makeText(SettingsFragment.this.getActivity(), "No text viewer is installed!", Toast.LENGTH_LONG).show();
			}
			return false;
		}
		return false;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if(data != null) {
			switch (requestCode) {
				case FILESOURCE_RESULT_CODE:
					Uri uri = data.getData();
					if (uri != null) {
						if(uri.toString().startsWith("file://"))
							((EditTextPreference) findPreference(getString(R.string.pref_filesource_file)))
										.setText(uri.toString().substring(7));
						else
							Toast.makeText(SettingsFragment.this.getActivity(), "Not a local file: " + uri.toString(), Toast.LENGTH_LONG).show();
					}
					break;
				default:
			}
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		updateSummaries();

		// Screen Orientation:
		String screenOrientation = sharedPreferences.getString(getString(R.string.pref_screenOrientation), "auto");
		setScreenOrientation(screenOrientation);
	}

	/**
	 * Will go through each preference element and initialize/update the summary according to its value
	 */
	public void updateSummaries() {
		// Source Type
		ListPreference listPref = (ListPreference) findPreference(getString(R.string.pref_sourceType));
		listPref.setSummary(getString(R.string.pref_sourceType_summ, listPref.getEntry()));

		// FileSource Frequency
		EditTextPreference editTextPref = (EditTextPreference) findPreference(getString(R.string.pref_filesource_frequency));
		editTextPref.setSummary(getString(R.string.pref_filesource_frequency_summ, editTextPref.getText()));

		// FileSource Sample Rate
		editTextPref = (EditTextPreference) findPreference(getString(R.string.pref_filesource_sampleRate));
		editTextPref.setSummary(getString(R.string.pref_filesource_sampleRate_summ, editTextPref.getText()));

		// FileSource File
		editTextPref = (EditTextPreference) findPreference(getString(R.string.pref_filesource_file));
		editTextPref.setSummary(getString(R.string.pref_filesource_file_summ, editTextPref.getText()));

		// FFT size
		listPref = (ListPreference) findPreference(getString(R.string.pref_fftSize));
		listPref.setSummary(getString(R.string.pref_fftSize_summ, listPref.getEntry()));

		// Screen Orientation
		listPref = (ListPreference) findPreference(getString(R.string.pref_screenOrientation));
		listPref.setSummary(getString(R.string.pref_screenOrientation_summ, listPref.getEntry()));

		// Frame Rate
		SwitchPreference switchPref = (SwitchPreference) findPreference(getString(R.string.pref_dynamicFrameRate));
		listPref = (ListPreference) findPreference(getString(R.string.pref_frameRate));
		if (switchPref.isChecked())
			listPref.setSummary(getString(R.string.pref_frameRate_summ, "auto"));
		else
			listPref.setSummary(getString(R.string.pref_frameRate_summ, listPref.getEntry()));

		// Logfile
		editTextPref = (EditTextPreference) findPreference(getString(R.string.pref_logfile));
		editTextPref.setSummary(getString(R.string.pref_logfile_summ, editTextPref.getText()));
	}

	/**
	 * Will set the screen orientation of the hosting activity
	 *
	 * @param orientation		auto, landscape, portrait, reverse_landscape or reverse_portrait
	 */
	public void setScreenOrientation(String orientation) {
		if(orientation.equals("auto"))
			getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
		else if(orientation.equals("landscape"))
			getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		else if(orientation.equals("portrait"))
			getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		else if(orientation.equals("reverse_landscape"))
			getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
		else if(orientation.equals("reverse_portrait"))
			getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
	}

	/**
	 * Extract the path from an uri
	 * This code was published on StackOverflow by dextor
	 *
	 * @param contentUri		uri that contains the file path
	 * @return absolute file path as string
	 */
	private String getRealPathFromURI(Uri contentUri) {
		String[] proj = { MediaStore.Images.Media.DATA };
		CursorLoader loader = new CursorLoader(this.getActivity(), contentUri, proj, null, null, null);
		Cursor cursor = loader.loadInBackground();
		int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
		cursor.moveToFirst();
		return cursor.getString(column_index);
	}
}
