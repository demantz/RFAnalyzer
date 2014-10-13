package com.mantz_it.rfanalyzer;

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
	 * @return the rate at which this source receives samples
	 */
	public int getSampleRate();

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
}
