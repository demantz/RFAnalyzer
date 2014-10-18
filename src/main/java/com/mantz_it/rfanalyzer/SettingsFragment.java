package com.mantz_it.rfanalyzer;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

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
public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);
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
}
