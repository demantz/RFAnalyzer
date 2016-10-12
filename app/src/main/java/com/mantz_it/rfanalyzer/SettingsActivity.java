package com.mantz_it.rfanalyzer;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * <h1>RF Analyzer - Settings Activity</h1>
 *
 * Module:      SettingsActivity.java
 * Description: This activity shows the settings fragment
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
public class SettingsActivity extends AppCompatActivity {

	private static final String LOGTAG = "SettingsActivity";
	public static final int PERMISSION_REQUEST_LOGGING_WRITE_FILES = 1000;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		SettingsFragment settingsFragment = new SettingsFragment();
		FragmentManager fragmentManager = getFragmentManager();
		FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
		fragmentTransaction.replace(android.R.id.content, settingsFragment);
		fragmentTransaction.commit();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
		switch (requestCode) {
			case PERMISSION_REQUEST_LOGGING_WRITE_FILES: {
				// If request is cancelled, the result arrays are empty.
				if (grantResults.length <= 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
					Log.i(LOGTAG, "onRequestPermissionResult: User denied to write files for logging. deactivate setting..");
					SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
					preferences.edit().putBoolean(getString(R.string.pref_logging), false).apply();
				}
			}
		}
	}
}
