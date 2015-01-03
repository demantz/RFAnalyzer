package com.mantz_it.rfanalyzer;

/**
 * <h1>RF Analyzer - Channel Control Interface</h1>
 *
 * Module:      ChannelControlInterface.java
 * Description: This interface offers methods to manipulate the current demodulation process.
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
public interface ChannelControlInterface {

	public void onUpdateDemodulationMode(int newDemodulationMode);

	/**
	 * Is called when the channel width should be adjusted.
	 *
	 * @param newChannelWidth	new channel width (single sided) in Hz
	 * @return true if valid width; false if width is out of range
	 */
	public boolean onUpdateChannelWidth(int newChannelWidth);

	/**
	 * Is called when the channel frequency should be adjusted.
	 *
	 * @param newChannelFrequency	new channel frequency in Hz
	 */
	public void onUpdateChannelFrequency(long newChannelFrequency);

	/**
	 * Is called when the signal strength of the selected channel
	 * crosses the squelch threshold
	 *
	 * @param squelchSatisfied	true: the signal is now stronger than the threshold; false: signal is now weaker
	 */
	public void onUpdateSquelchSatisfied(boolean squelchSatisfied);

	/**
	 * Is called to determine the current channel width
	 *
	 * @return	the current channel width
	 */
	public int onCurrentChannelWidthRequested();
}
