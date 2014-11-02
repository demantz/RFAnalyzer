/*
 *  Copyright 2006-2007 Columbia University.
 *
 *  This file is part of MEAPsoft.
 *
 *  MEAPsoft is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 *
 *  MEAPsoft is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with MEAPsoft; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 *  02110-1301 USA
 *
 *  See the file "COPYING" for the text of the license.
 */

package com.mantz_it.rfanalyzer;


public class FFT {

	int n, m;

	// Lookup tables.  Only need to recompute when size of FFT changes.
	float[] cos;
	float[] sin;

	float[] window;

	public FFT(int n) {
		this.n = n;
		this.m = (int)(Math.log(n) / Math.log(2));

		// Make sure n is a power of 2
		if(n != (1<<m))
			throw new RuntimeException("FFT length must be power of 2");

		// precompute tables
		cos = new float[n/2];
		sin = new float[n/2];

//     for(int i=0; i<n/4; i++) {
//       cos[i] = Math.cos(-2*Math.PI*i/n);
//       sin[n/4-i] = cos[i];
//       cos[n/2-i] = -cos[i];
//       sin[n/4+i] = cos[i];
//       cos[n/2+i] = -cos[i];
//       sin[n*3/4-i] = -cos[i];
//       cos[n-i]   = cos[i];
//       sin[n*3/4+i] = -cos[i];        
//     }

		for(int i=0; i<n/2; i++) {
			cos[i] = (float) Math.cos(-2*Math.PI*i/n);
			sin[i] = (float) Math.sin(-2*Math.PI*i/n);
		}

		makeWindow();
	}

	protected void makeWindow() {
		// Make a blackman window:
		// w(n)=0.42-0.5cos{(2*PI*n)/(N-1)}+0.08cos{(4*PI*n)/(N-1)};
		window = new float[n];
		for(int i = 0; i < window.length; i++)
			window[i] = (float) (0.42 - 0.5 * Math.cos(2*Math.PI*i/(n-1))
					+ 0.08 * Math.cos(4*Math.PI*i/(n-1)));
	}

	public float[] getWindow() {
		return window;
	}

	public void applyWindow(float[] re, float[] im) {
		for (int i = 0; i < window.length; i++) {
			re[i] = window[i] * re[i];
			im[i] = window[i] * im[i];
		}
	}


	/***************************************************************
	 * fft.c
	 * Douglas L. Jones
	 * University of Illinois at Urbana-Champaign
	 * January 19, 1992
	 * http://cnx.rice.edu/content/m12016/latest/
	 *
	 *   fft: in-place radix-2 DIT DFT of a complex input
	 *
	 *   input:
	 * n: length of FFT: must be a power of two
	 * m: n = 2**m
	 *   input/output
	 * x: double array of length n with real part of data
	 * y: double array of length n with imag part of data
	 *
	 *   Permission to copy and use this program is granted
	 *   as long as this header is included.
	 ****************************************************************/
	public void fft(float[] x, float[] y)
	{
		int i,j,k,n1,n2,a;
		float c,s,e,t1,t2;


		// Bit-reverse
		j = 0;
		n2 = n/2;
		for (i=1; i < n - 1; i++) {
			n1 = n2;
			while ( j >= n1 ) {
				j = j - n1;
				n1 = n1/2;
			}
			j = j + n1;

			if (i < j) {
				t1 = x[i];
				x[i] = x[j];
				x[j] = t1;
				t1 = y[i];
				y[i] = y[j];
				y[j] = t1;
			}
		}

		// FFT
		n1 = 0;
		n2 = 1;

		for (i=0; i < m; i++) {
			n1 = n2;
			n2 = n2 + n2;
			a = 0;

			for (j=0; j < n1; j++) {
				c = cos[a];
				s = sin[a];
				a +=  1 << (m-i-1);

				for (k=j; k < n; k=k+n2) {
					t1 = c*x[k+n1] - s*y[k+n1];
					t2 = s*x[k+n1] + c*y[k+n1];
					x[k+n1] = x[k] - t1;
					y[k+n1] = y[k] - t2;
					x[k] = x[k] + t1;
					y[k] = y[k] + t2;
				}
			}
		}
	}
}
