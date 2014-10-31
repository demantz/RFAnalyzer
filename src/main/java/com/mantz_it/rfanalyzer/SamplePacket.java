package com.mantz_it.rfanalyzer;

/**
 * <h1>RF Analyzer - Sample Packet</h1>
 *
 * Module:      SamplePacket.java
 * Description: This class encapsulates a packet of complex samples.
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
public class SamplePacket {
	private double[] re;		// real values
	private double[] im;		// imag values
	private long frequency;		// center frequency
	private int sampleRate;		// sample rate
	private int size;			// number of samples in this packet

	/**
	 * Constructor. This constructor wraps existing arrays and set the number of
	 * samples to the length of the arrays
	 *
	 * @param re	array of real parts of the sample values
	 * @param im	array of imaginary parts of the sample values
	 * @param frequency		center frequency
	 * @param sampleRate	sample rate
	 */
	public SamplePacket(double[] re, double im[], long frequency, int sampleRate) {
		this(re, im, frequency, sampleRate, re.length);
	}

	/**
	 * Constructor. This constructor wraps existing arrays and allows to set the
	 * number of samples in this packet to something smaller than the array length
	 *
	 * @param re	array of real parts of the sample values
	 * @param im	array of imaginary parts of the sample values
	 * @param frequency		center frequency
	 * @param sampleRate	sample rate
	 * @param size	number of samples in this packet ( <= arrays.length )
	 */
	public SamplePacket(double[] re, double im[], long frequency, int sampleRate, int size) {
		if(re.length != im.length)
			throw new IllegalArgumentException("Arrays must be of the same length");
		if(size > re.length)
			throw new IllegalArgumentException("Size must be of the smaller or equal the array length");
		this.re = re;
		this.im = im;
		this.frequency = frequency;
		this.sampleRate = sampleRate;
		this.size = size;
	}

	/**
	 * Constructor. This constructor allocates two fresh arrays
	 *
	 * @param size	Number of samples in this packet
	 */
	public SamplePacket(int size) {
		this.re = new double[size];
		this.im = new double[size];
		this.frequency = 0;
		this.sampleRate = 0;
		this.size = 0;
	}

	/**
	 * @return the reference to the array of real parts
	 */
	public double[] re() {
		return re;
	}

	/**
	 * Returns the real part at the specified index
	 *
	 * @param i		index
	 * @return real part of the sample with the given index
	 */
	public double re(int i) {
		return re[i];
	}

	/**
	 * @return the reference to the array of imaginary parts
	 */
	public double[] im() {
		return im;
	}

	/**
	 * Returns the imaginary part at the specified index
	 *
	 * @param i		index
	 * @return imaginary part of the sample with the given index
	 */
	public double im(int i) {
		return im[i];
	}

	/**
	 * @return the length of the arrays
	 */
	public int capacity() {
		return re.length;
	}

	/**
	 * @return number of samples in this packet
	 */
	public int size() {
		return size;
	}

	/**
	 * Sets a new size (number of samples in this packet)
	 * @param size	number of (valid) samples in this packet
	 */
	public void setSize(int size) {
		this.size = Math.min(size, re.length);
	}

	/**
	 * @return center frequency at which these samples where recorded
	 */
	public long getFrequency() {
		return frequency;
	}

	/**
	 * @return sample rate at which these samples were recorded
	 */
	public int getSampleRate() {
		return sampleRate;
	}

	/**
	 * Sets the center frequency for this sample packet
	 * @param frequency		center frequency at which these samples were recorded
	 */
	public void setFrequency(long frequency) {
		this.frequency = frequency;
	}

	/**
	 * Sets the sample rate for this sample packet
	 * @param sampleRate		sample rate at which these samples were recorded
	 */
	public void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
	}
}
