package com.mantz_it.rfanalyzer;

import android.util.Log;

/**
 * <h1>RF Analyzer - Half Band Low Pass Filter</h1>
 *
 * Module:      HalfBandLowPassFilter.java
 * Description: This class implements a half-band lowpass filter that decimates by 2.
 *              In order to gain performance this is a very specific and unflexible
 *              implementation. It is used to downsample a high rate signal.
 *              NOTE: This filter amplifies the signal by factor 2!
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
public class HalfBandLowPassFilter {

	private double[] taps;
	private double[] delaysReal;
	private double[] delaysImag;
	private double[] delaysMiddleTapReal;
	private double[] delaysMiddleTapImag;
	private int delayIndex;
	private int delayMiddleTapIndex;
	private static final String LOGTAG = "HalfBandLowPassFilter";

	/**
	 * Constructor. Will allocate the delay arrays.
	 * @param N
	 */
	public HalfBandLowPassFilter (int N) {
		if(N % 4 != 0)
			throw new IllegalArgumentException("N must be multiple of 4");
		delaysReal = new double[N/2];
		delaysImag = new double[N/2];
		delaysMiddleTapReal = new double[N/4];
		delaysMiddleTapImag = new double[N/4];
		delayIndex = 0;
		delayMiddleTapIndex = 0;

		// Taps where generated with scipy:
		// todo: generate them dynamically!
		switch (N) {
			case 8:		taps = new double[] {-0.045567308121, 0.550847429795};
						break;
			case 12:	taps = new double[] {0.018032677037, -0.114591559026, 0.597385968973};
						break;
			case 32:	taps = new double[] {-0.020465752391, 0.021334704213, -0.032646869627, 0.048752407464,
											 -0.072961784639, 0.113978914053, -0.203982998267, 0.633841612044 };
						break;
			default:	throw new IllegalArgumentException("N=" + N + " is not supported!");
		}
	}

	/**
	 * Filters the samples from the input sample packet and appends filter output to the output
	 * sample packet. Stops automatically if output sample packet is full.
	 *
	 * This method uses a half band low pass filter with N taps. It will decimate by 2 and
	 * amplify the signal by 2.
	 *
	 * @param in		input sample packet
	 * @param out		output sample packet
	 * @param offset	offset to use as start index for the input packet
	 * @param length	max number of samples processed from the input packet
	 * @return number of samples consumed from the input packet
	 */
	public int filter(SamplePacket in, SamplePacket out, int offset, int length) {
		int index1, index2;
		int indexOut = out.size();
		int outputCapacity = out.capacity();
		double[] reIn = in.re(), imIn = in.im(), reOut = out.re(), imOut = out.im();

		// insert each input sample into the delay line:
		for (int i = offset; i < length-1; i+=2) {
			// first check if we have enough space in the output buffers:
			if(indexOut == outputCapacity) {
				out.setSize(indexOut);	// update size of output sample packet
				out.setSampleRate(in.getSampleRate()/2);	// update the sample rate of the output sample packet
				return i-offset;    // We return the number of consumed samples from the input buffers
			}

			// Insert samples in delay line:
			delaysReal[delayIndex] = reIn[i];
			delaysImag[delayIndex] = imIn[i];
			delaysMiddleTapReal[delayMiddleTapIndex] = reIn[i+1];
			delaysMiddleTapImag[delayMiddleTapIndex] = imIn[i+1];

			// Update middle tap index so that it points to the oldest sample:
			delayMiddleTapIndex++;
			if(delayMiddleTapIndex >= delaysMiddleTapReal.length)
				delayMiddleTapIndex = 0;

			// Calculate the results:
			reOut[indexOut] = 0;
			imOut[indexOut] = 0;
			index1 = delayIndex;
			index2 = delayIndex+delaysReal.length-1;
			for (double tap : taps) {
				reOut[indexOut] += (delaysReal[index1] + delaysReal[index2]) * tap;
				imOut[indexOut] += (delaysImag[index1] + delaysImag[index2]) * tap;
				index1++;
				index2--;
				if(index1 > delaysReal.length)	index1 = 0;
				if(index2 < 0) index2 = delaysReal.length -1;
			}
			reOut[indexOut] += delaysMiddleTapReal[delayMiddleTapIndex];
			imOut[indexOut] += delaysMiddleTapImag[delayMiddleTapIndex];
			indexOut++;
		}
		out.setSize(indexOut);	// update size of output sample packet
		out.setSampleRate(in.getSampleRate()/2);	// update the sample rate of the output sample packet
		return length;			// We return the number of consumed samples from the input buffers
	}

	/**
	 * Filters the samples from the input sample packet and appends filter output to the output
	 * sample packet. Stops automatically if output sample packet is full.
	 *
	 * This method uses a half band low pass filter with 8 taps. It will decimate by 2 and
	 * amplify the signal by 2. First 30% of the output signal frequency spectrum are protected
	 * from aliasing (-30dB)
	 *
	 * @param in		input sample packet
	 * @param out		output sample packet
	 * @param offset	offset to use as start index for the input packet
	 * @param length	max number of samples processed from the input packet
	 * @return number of samples consumed from the input packet
	 */
	public int filterN8(SamplePacket in, SamplePacket out, int offset, int length) {
		int indexOut = out.size();
		int outputCapacity = out.capacity();
		double[] reIn = in.re(), imIn = in.im(), reOut = out.re(), imOut = out.im();

		// insert each input sample into the delay line:
		for (int i = offset; i < length-1; i+=2) {
			// first check if we have enough space in the output buffers:
			if(indexOut == outputCapacity) {
				out.setSize(indexOut);	// update size of output sample packet
				out.setSampleRate(in.getSampleRate()/2);	// update the sample rate of the output sample packet
				return i-offset;    // We return the number of consumed samples from the input buffers
			}

			// Insert samples in delay line:
			delaysReal[delayIndex] = reIn[i];
			delaysImag[delayIndex] = imIn[i];
			delaysMiddleTapReal[delayMiddleTapIndex] = reIn[i+1];
			delaysMiddleTapImag[delayMiddleTapIndex] = imIn[i+1];

			// Update middle tap index so that it points to the oldest sample:
			delayMiddleTapIndex++;
			if(delayMiddleTapIndex >= 2)
				delayMiddleTapIndex = 0;

			// Calculate the results:
			// note that this is fast but not very elegant xD
			switch (delayIndex) {
				case 0:
					reOut[indexOut] = (delaysReal[0] + delaysReal[3]) * -0.045567308121
									+ (delaysReal[1] + delaysReal[2]) * 0.550847429795
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[0] + delaysImag[3]) * -0.045567308121
									+ (delaysImag[1] + delaysImag[2]) * 0.550847429795
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 1;
					break;
				case 1:
					reOut[indexOut] = (delaysReal[1] + delaysReal[0]) * -0.045567308121
									+ (delaysReal[2] + delaysReal[3]) * 0.550847429795
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[1] + delaysImag[0]) * -0.045567308121
									+ (delaysImag[2] + delaysImag[3]) * 0.550847429795
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 2;
					break;
				case 2:
					reOut[indexOut] = (delaysReal[2] + delaysReal[1]) * -0.045567308121
									+ (delaysReal[3] + delaysReal[0]) * 0.550847429795
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[2] + delaysImag[1]) * -0.045567308121
									+ (delaysImag[3] + delaysImag[0]) * 0.550847429795
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 3;
					break;
				case 3:
					reOut[indexOut] = (delaysReal[3] + delaysReal[2]) * -0.045567308121
									+ (delaysReal[0] + delaysReal[1]) * 0.550847429795
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[3] + delaysImag[2]) * -0.045567308121
									+ (delaysImag[0] + delaysImag[1]) * 0.550847429795
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 0;
					break;
				default:
					Log.e(LOGTAG,"filterN8: illegal delayIndex value: " + delayIndex);
			}
			indexOut++;
		}
		out.setSize(indexOut);	// update size of output sample packet
		out.setSampleRate(in.getSampleRate()/2);	// update the sample rate of the output sample packet
		return length;			// We return the number of consumed samples from the input buffers
	}

	/**
	 * Filters the samples from the input sample packet and appends filter output to the output
	 * sample packet. Stops automatically if output sample packet is full.
	 *
	 * This method uses a half band low pass filter with 12 taps. It will decimate by 2 and
	 * amplify the signal by 2. First 50% of the input signal frequency spectrum are protected
	 * from aliasing (-30dB)
	 *
	 * @param in		input sample packet
	 * @param out		output sample packet
	 * @param offset	offset to use as start index for the input packet
	 * @param length	max number of samples processed from the input packet
	 * @return number of samples consumed from the input packet
	 */
	public int filterN12(SamplePacket in, SamplePacket out, int offset, int length) {
		int indexOut = out.size();
		int outputCapacity = out.capacity();
		double[] reIn = in.re(), imIn = in.im(), reOut = out.re(), imOut = out.im();

		// insert each input sample into the delay line:
		for (int i = offset; i < length-1; i+=2) {
			// first check if we have enough space in the output buffers:
			if(indexOut == outputCapacity) {
				out.setSize(indexOut);	// update size of output sample packet
				out.setSampleRate(in.getSampleRate()/2);	// update the sample rate of the output sample packet
				return i-offset;    // We return the number of consumed samples from the input buffers
			}

			// Insert samples in delay line:
			delaysReal[delayIndex] = reIn[i];
			delaysImag[delayIndex] = imIn[i];
			delaysMiddleTapReal[delayMiddleTapIndex] = reIn[i+1];
			delaysMiddleTapImag[delayMiddleTapIndex] = imIn[i+1];

			// Update middle tap index so that it points to the oldest sample:
			delayMiddleTapIndex++;
			if(delayMiddleTapIndex >= 3)
				delayMiddleTapIndex = 0;

			// Calculate the results:
			// note that this is fast but not very elegant xD
			switch (delayIndex) {
				case 0:
					reOut[indexOut] = (delaysReal[0] + delaysReal[5]) * 0.018032677037
									+ (delaysReal[1] + delaysReal[4]) * -0.114591559026
									+ (delaysReal[2] + delaysReal[3]) * 0.597385968973
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[0] + delaysImag[5]) * 0.018032677037
									+ (delaysImag[1] + delaysImag[4]) * -0.114591559026
									+ (delaysImag[2] + delaysImag[3]) * 0.597385968973
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 1;
					break;
				case 1:
					reOut[indexOut] = (delaysReal[1] + delaysReal[0]) * 0.018032677037
									+ (delaysReal[2] + delaysReal[5]) * -0.114591559026
									+ (delaysReal[3] + delaysReal[4]) * 0.597385968973
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[1] + delaysImag[0]) * 0.018032677037
									+ (delaysImag[2] + delaysImag[5]) * -0.114591559026
									+ (delaysImag[3] + delaysImag[4]) * 0.597385968973
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 2;
					break;
				case 2:
					reOut[indexOut] = (delaysReal[2] + delaysReal[1]) * 0.018032677037
									+ (delaysReal[3] + delaysReal[0]) * -0.114591559026
									+ (delaysReal[4] + delaysReal[5]) * 0.597385968973
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[2] + delaysImag[1]) * 0.018032677037
									+ (delaysImag[3] + delaysImag[0]) * -0.114591559026
									+ (delaysImag[4] + delaysImag[5]) * 0.597385968973
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 3;
					break;
				case 3:
					reOut[indexOut] = (delaysReal[3] + delaysReal[2]) * 0.018032677037
									+ (delaysReal[4] + delaysReal[1]) * -0.114591559026
									+ (delaysReal[5] + delaysReal[0]) * 0.597385968973
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[3] + delaysImag[2]) * 0.018032677037
									+ (delaysImag[4] + delaysImag[1]) * -0.114591559026
									+ (delaysImag[5] + delaysImag[0]) * 0.597385968973
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 4;
					break;
				case 4:
					reOut[indexOut] = (delaysReal[4] + delaysReal[3]) * 0.018032677037
									+ (delaysReal[5] + delaysReal[2]) * -0.114591559026
									+ (delaysReal[0] + delaysReal[1]) * 0.597385968973
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[4] + delaysImag[3]) * 0.018032677037
									+ (delaysImag[5] + delaysImag[2]) * -0.114591559026
									+ (delaysImag[0] + delaysImag[1]) * 0.597385968973
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 5;
					break;
				case 5:
					reOut[indexOut] = (delaysReal[5] + delaysReal[4]) * 0.018032677037
									+ (delaysReal[0] + delaysReal[3]) * -0.114591559026
									+ (delaysReal[1] + delaysReal[2]) * 0.597385968973
									+ delaysMiddleTapReal[delayMiddleTapIndex];
					imOut[indexOut] = (delaysImag[5] + delaysImag[4]) * 0.018032677037
									+ (delaysImag[0] + delaysImag[3]) * -0.114591559026
									+ (delaysImag[1] + delaysImag[2]) * 0.597385968973
									+ delaysMiddleTapImag[delayMiddleTapIndex];
					delayIndex = 0;
					break;
				default:
					Log.e(LOGTAG,"filterN12: illegal delayIndex value: " + delayIndex);
			}
			indexOut++;
		}
		out.setSize(indexOut);	// update size of output sample packet
		out.setSampleRate(in.getSampleRate()/2);	// update the sample rate of the output sample packet
		return length;			// We return the number of consumed samples from the input buffers
	}

}
