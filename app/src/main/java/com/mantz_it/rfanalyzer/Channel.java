package com.mantz_it.rfanalyzer;

/**
 * <h1>RF Analyzer - Channel</h1>
 *
 * Module:      Channel.java
 * Description: This class represents a channel.
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
public class Channel {
	private long frequency;
	private int bandwidth;
	private int mode;			// See Demodulator: demodulation mode
	private long timestamp;		// This might be used differently, e.g. it could be the last time this channel was seen active or logged..
	private float level;		// in dB

	public Channel(long frequency, int bandwidth, int mode) {
		this.frequency = frequency;
		this.bandwidth = bandwidth;
		this.mode = mode;
		this.timestamp = 0;
		this.level = Float.NaN;
	}

	public long getFrequency() {
		return frequency;
	}

	public void setFrequency(long frequency) {
		this.frequency = frequency;
	}

	public int getBandwidth() {
		return bandwidth;
	}

	public void setBandwidth(int bandwidth) {
		this.bandwidth = bandwidth;
	}

	public int getMode() {
		return mode;
	}

	public void setMode(int mode) {
		this.mode = mode;
	}

	public long getStartFrequency() {
		return frequency - bandwidth/2;
	}

	public long getEndFrequency() {
		return frequency + bandwidth/2;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	public float getLevel() {
		return level;
	}

	public void setLevel(float level) {
		this.level = level;
	}
}
