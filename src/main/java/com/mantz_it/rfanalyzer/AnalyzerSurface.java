package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * <h1>RF Analyzer - Analyzer Surface</h1>
 *
 * Module:      AnalyzerSurface.java
 * Description: This is a custom view extending the SurfaceView.
 *              It will show the frequency spectrum and the waterfall
 *              diagram.
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
public class AnalyzerSurface extends SurfaceView implements SurfaceHolder.Callback {

	private Paint fftPaint = null;		// Paint object to draw the fft lines
	private Paint textPaint = null;		// Paint object to draw text on the canvas
	private int width;					// current width (in pixels) of the SurfaceView
	private int height;					// current height (in pixels) of the SurfaceView
	private static final String logtag = "AnalyzerSurface";

	/**
	 * Constructor. Will initialize the Paint instances and register the callback
	 * functions of the SurfaceHolder
	 *
	 * @param context
	 */
	public AnalyzerSurface(Context context) {
		super(context);
		this.fftPaint = new Paint();
		this.fftPaint.setColor(Color.BLUE);
		this.fftPaint.setStyle(Paint.Style.FILL);
		this.textPaint = new Paint();
		this.textPaint.setColor(Color.WHITE);

		// Add a Callback to get informed when the dimensions of the SurfaceView changes:
		this.getHolder().addCallback(this);
	}

	/**
	 * SurfaceHolder.Callback function. Gets called when the surface view is created
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceCreated(SurfaceHolder holder) {

	}

	/**
	 * SurfaceHolder.Callback function. This is called every time the dimension changes
	 * (and after the SurfaceView is created)
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		AnalyzerSurface.this.width = width;
		AnalyzerSurface.this.height = height;
	}

	/**
	 * SurfaceHolder.Callback function. Gets called before the surface view is destroyed
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {

	}

	/**
	 * Returns the y coordinate of the bottom line of the fft spectrum
	 *
	 * @return baseline (y coordinate) of the fft
	 */
	private int getFFTBaseline() {
		return height / 2;
	}

	/**
	 * This method will draw the fft, waterfall and the overlaying grid onto the given canvas
	 *
	 * @param c				canvas object to draw on
	 * @param mag			array of magnitude values that represent the fft
	 * @param sampleRate	sampleRate that was used to record the samples used to create the fft
	 * @param basebandFrequency		absolute frequency that should be used as center for the grid
	 * @param frameRate		current frame rate (only to draw it on the canvas)
	 * @param load			current load (number from 0 to 1). (only to draw it on the canvas)
	 */
	public void drawFrame(Canvas c, double[] mag, int sampleRate, long basebandFrequency, int frameRate, double load) {
		// clear the canvas:
		c.drawColor(Color.BLACK);

		// draw the fft:
		drawFFT(c, mag);

		// draw the waterfall:
		//drawWaterfall(...);

		// draw the grid (and some more information):
		drawGrid(c, sampleRate, basebandFrequency, frameRate, load);
	}

	/**
	 * This method will draw the fft onto the given canvas
	 *
	 * @param c				canvas object to draw on
	 * @param mag			array of magnitude values that represent the fft
	 */
	private void drawFFT(Canvas c, double[] mag) {
		float maxDB = 0;
		float minDB = -80;
		float baseline = getFFTBaseline();	// y coordinate of the bottom line of the fft
		float sampleWidth 	= (float) this.width / (float) mag.length;	// Size (in pixel) per one fft sample
		float dbWidth 		= baseline / (float) Math.abs(maxDB - minDB); 	// Size (in pixel) per 1dB

		float position = 0;
		for (int i = 0; i < mag.length; i++) {
			if(mag[i] > minDB) {
				float topPixel = (float) (baseline - (mag[i] - minDB) * dbWidth);
				if(topPixel < 0 ) topPixel = 0;
				c.drawRect(position, topPixel, position + sampleWidth, baseline, fftPaint);
			}
			position += sampleWidth;
		}
	}

	/**
	 * This method will draw the grid and some additional information onto the given canvas
	 *
	 * @param c				canvas object to draw on
	 * @param sampleRate	sampleRate that was used to record the samples used to create the fft
	 * @param basebandFrequency		absolute frequency that should be used as center for the grid
	 * @param frameRate		current frame rate (only to draw it on the canvas)
	 * @param load			current load (number from 0 to 1). (only to draw it on the canvas)
	 */
	private void drawGrid(Canvas c, int sampleRate, long basebandFrequency, int frameRate, double load) {
		c.drawText(frameRate+" FPS",10,15,textPaint);
		String loadStr = String.format("%3.1f %%",load*100);
		c.drawText(loadStr,10,30,textPaint);
	}
}
