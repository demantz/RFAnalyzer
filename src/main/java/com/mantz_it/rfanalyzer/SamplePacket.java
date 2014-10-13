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
	private double[] re;	// real values
	private double[] im;	// imag values

	/**
	 * Constructor. This constructor wraps existing arrays.
	 *
	 * @param re	array of real parts of the sample values
	 * @param im	array of imaginary parts of the sample values
	 */
	public SamplePacket(double[] re, double im[]) {
		if(re.length != im.length)
			throw new IllegalArgumentException("Arrays must be of the same length");
		this.re = re;
		this.im = im;
	}

	/**
	 * Constructor. This constructor allocates two fresh arrays
	 *
	 * @param size	Number of samples in this packet
	 */
	public SamplePacket(int size) {
		this.re = new double[size];
		this.im = new double[size];
	}

	public double[] re() {
		return re;
	}

	public double re(int i) {
		return re[i];
	}

	public double[] im() {
		return im;
	}

	public double im(int i) {
		return im[i];
	}

	public int size() {
		return re.length;
	}
}
