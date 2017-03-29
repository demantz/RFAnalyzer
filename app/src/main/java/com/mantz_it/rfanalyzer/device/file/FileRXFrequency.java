package com.mantz_it.rfanalyzer.device.file;

import android.util.Log;

import com.mantz_it.rfanalyzer.sdr.controls.RXFrequency;

/**
 * Created by Pavel on 28.03.2017.
 */
class FileRXFrequency implements RXFrequency {
	private FileIQSource fileIQSource;
	private final String LOGTAG = "[File]:RXFrequency";

	public FileRXFrequency(FileIQSource fileIQSource) {this.fileIQSource = fileIQSource;}

	@Override
	public Long get() {
		return fileIQSource.frequency;
	}

	@Override
	public void set(Long frequency) {
		Log.e(LOGTAG, "Setting the frequency is not supported on a file source");
	}

	@Override
	public Long getMax() {
		return fileIQSource.frequency;
	}

	@Override
	public Long getMin() {
		return fileIQSource.frequency;
	}

@Override
public int getFrequencyShift() {
	return 0;
}

@Override
public void setFrequencyShift(int frequencyShift) {
	throw new UnsupportedOperationException();
}
}
