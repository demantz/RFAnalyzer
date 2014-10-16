package com.mantz_it.rfanalyzer;

import android.content.Context;

/**
 * <h1>RF Analyzer - IQ Source Interface</h1>
 *
 * Module:      IQSourceInterface.java
 * Description: This interface represents a source of IQ samples. It allows the Scheduler to
 *              be independent of the SDR hardware.
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
public interface IQSourceInterface {

	/**
	 * Will open the device. This is usually an asynchronous action and therefore uses the
	 * callback function onIQSourceReady() from the Callback interface to notify the application
	 * when the IQSource is ready to use.
	 *
	 * @param context		needed to open external devices
	 * @param callback		reference to a class that implements the Callback interface for notification
	 * @return false if an error occurred.
	 */
	public boolean open(Context context, Callback callback);

	/**
	 * Will return true if the source is opened and ready to use
	 *
	 * @return true if open, false if not open
	 */
	public boolean isOpen();

	/**
	 * Will close the device.
	 *
	 * @return false if an error occurred.
	 */
	public boolean close();

	/**
	 * @return a human readable Name of the source
	 */
	public String getName();

	/**
	 * @return the rate at which this source receives samples
	 */
	public int getSampleRate();

	/**
	 * @param sampleRate	sample rate that should be set for the IQ source
	 */
	public void setSampleRate(int sampleRate);

	/**
	 * @return the Frequency to which the source is tuned
	 */
	public long getFrequency();

	/**
	 * @param frequency		frequency to which the IQ source should be tuned
	 */
	public void setFrequency(long frequency);


	/**
	 * @return the maximum frequency to which the source can be tuned
	 */
	public long getMaxFrequency();

	/**
	 * @return the minimum frequency to which the source can be tuned
	 */
	public long getMinFrequency();

	/**
	 * @return the maximum sample rate to which the source can be set
	 */
	public long getMaxSampleRate();

	/**
	 * @return the minimum sample rate to which the source can be set
	 */
	public long getMinSampleRate();

	/**
	 * @return the size (in byte) of a packet that is returned by getPacket()
	 */
	public int getPacketSize();

	/**
	 * This method will grab the next packet from the source and return it. If no
	 * packet is available after the timeout, null is returned. Make sure to return
	 * the packet to the buffer pool by using returnPacket() after it is no longer used.
	 *
	 * @return packet containing received samples
	 */
	public byte[] getPacket(int timeout);

	/**
	 * This method will return the given buffer (packet) to the buffer pool of the
	 * source instance.
	 *
	 * @param buffer	A packet that was returned by getPacket() and is now no longer used
	 */
	public void returnPacket(byte[] buffer);

	/**
	 * Start receiving samples.
	 */
	public void startSampling();

	/**
	 * Stop receiving samples.
	 */
	public void stopSampling();

	/**
	 * Used to convert a packet from this source to the SamplePacket format. That means the samples
	 * in the SamplePacket are stored as signed double values, normalized between -1 and 1.
	 *
	 * @param packet		packet that was returned by getPacket() and that should now be 'filled'
	 *                      into the samplePacket.
	 * @param samplePacket	SamplePacket that should be filled with samples from the packet.
	 * @param startIndex	First index in samplePacket that should be written.
	 * @return the number of samples filled into the samplePacket.
	 */
	public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket samplePacket, int startIndex);

	/**
	 * Callback interface for asynchronous interactions with the source.
	 */
	public static interface Callback {
		/**
		 * This method will be called when the source is ready to use after the application
		 * called open()
		 *
		 * @param source	A reference to the IQSource that is now ready
		 */
		public void onIQSourceReady(IQSourceInterface source);

		/**
		 * This method will be called when there is an error with the source
		 *
		 * @param source	A reference to the IQSource that caused the error
		 * @param message	Description of the error
		 */
		public void onIQSourceError(IQSourceInterface source, String message);
	}
}
