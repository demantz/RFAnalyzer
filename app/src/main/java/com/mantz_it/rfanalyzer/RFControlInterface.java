package com.mantz_it.rfanalyzer;

/**
 * <h1>RF Analyzer - RF Control Interface</h1>
 *
 * Module:      RFControlInterface.java
 * Description: This interface offers methods to manipulate the source configuration
 *              and signal processing.
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
public interface RFControlInterface {

	public void updateDemodulationMode(int newDemodulationMode);

	/**
	 * Is called to adjust the channel width.
	 *
	 * @param newChannelWidth	new channel width (single sided) in Hz
	 * @return true if valid width; false if width is out of range
	 */
	public boolean updateChannelWidth(int newChannelWidth);

	/**
	 * Is called to adjust the channel frequency.
	 *
	 * @param newChannelFrequency	new channel frequency in Hz
	 */
	public void updateChannelFrequency(long newChannelFrequency);

	/**
	 * Is called to adjust the frequency of the signal source.
	 *
	 * @param newSourceFrequency	new source frequency in Hz
	 */
	public void updateSourceFrequency(long newSourceFrequency);

	/**
	 * Is called to adjust the sample rate of the signal source.
	 *
	 * @param newSampleRate			new sample rate in Sps
	 */
	public void updateSampleRate(int newSampleRate);

	/**
	 * Is called when the signal strength of the selected channel
	 * crosses the squelch threshold
	 *
	 * @param squelchSatisfied	true: the signal is now stronger than the threshold; false: signal is now weaker
	 */
	public void updateSquelchSatisfied(boolean squelchSatisfied);

	/**
	 * Is called to determine the current channel width
	 *
	 * @return	the current channel width
	 */
	public int requestCurrentChannelWidth();

	/**
	 * Is called to determine the current channel frequency
	 *
	 * @return	the current channel frequency
	 */
	public long requestCurrentChannelFrequency();

	/**
	 * Is called to determine the current demodulation mode
	 * @return the current demodulation mode (Demodulator.DEMODULATION_OFF, *_AM, *_FM, ...)
	 */
	public int requestCurrentDemodulationMode();

	/**
	 * Is called to determine the current squelch setting
	 * @return the current squelch setting (in dB)
	 */
	public float requestCurrentSquelch();

	/**
	 * Is called to determine the current source frequency
	 *
	 * @return	the current frequency of the signal source
	 */
	public long requestCurrentSourceFrequency();

	/**
	 * Is called to determine the current sample rate of the signal source
	 *
	 * @return	the current sample rate of the signal source
	 */
	public int requestCurrentSampleRate();

	/**
	 * Is called to determine the maximum source frequency
	 *
	 * @return	the maximum frequency of the signal source
	 */
	public long requestMaxSourceFrequency();

	/**
	 * Is called to determine the sample rates supported by the signal source
	 *
	 * @return	array of all supported sample rates
	 */
	public int[] requestSupportedSampleRates();
}
