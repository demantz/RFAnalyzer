package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
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

	private Paint fftPaint = null;			// Paint object to draw the fft lines
	private Paint waterfallPaint = null; 	// Paint object to draw the waterfall bitmaps
	private Paint waterfallLinePaint = null;// Paint object to draw one waterfall pixel
	private Paint textPaint = null;			// Paint object to draw text on the canvas
	private int width;						// current width (in pixels) of the SurfaceView
	private int height;						// current height (in pixels) of the SurfaceView
	private static final String logtag = "AnalyzerSurface";

	private int[] waterfallColorMap = null;		// Colors used to draw the waterfall plot.
												// idx 0 -> weak signal   idx max -> strong signal
	private Bitmap[] waterfallBitmaps = null;	// Each array element holds one line in the waterfall plot
	private int waterfallBitmapTopIndex = 0;	// Indicates which array index in waterfallBitmaps
												// is the most recent (circular array)

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
		this.waterfallPaint = new Paint();
		this.waterfallLinePaint = new Paint();

		// Add a Callback to get informed when the dimensions of the SurfaceView changes:
		this.getHolder().addCallback(this);

		// Create the color map for the waterfall plot (should be customizable later)
		this.createWaterfallColorMap();
	}

	/**
	 * Will initialize the waterfallBitmaps array for the given width and height of the waterfall plot.
	 * If the array is not null, it will be recycled first.
	 *
	 * @param width				width (in pixel) of the waterfall plot
	 * @param height			height (in pixel) of the waterfall plot (must be multiple of pixelPerLine)
	 * @param pixelPerLine		height (in pixel) of one line in the waterfall plot
	 */
	private void createWaterfallBitmaps(int width, int height, int pixelPerLine) {
//		// check if height is multiple of pixelPerLine:
//		if(height % pixelPerLine != 0)
//			throw new IllegalArgumentException("Height (" + height + ") must be multiple of PixelPerLine ("+pixelPerLine+")!");

		// Recycle bitmaps if not null:
		if(this.waterfallBitmaps != null) {
			for(Bitmap b: this.waterfallBitmaps)
				b.recycle();
		}

		// Create new array:
		this.waterfallBitmapTopIndex = 0;
		this.waterfallBitmaps = new Bitmap[height/pixelPerLine];
		for (int i = 0; i < waterfallBitmaps.length; i++)
			waterfallBitmaps[i] = Bitmap.createBitmap(width,pixelPerLine, Bitmap.Config.ARGB_8888);
	}

	/**
	 * Will populate the waterfallColorMap array with color instances
	 */
	public void createWaterfallColorMap() {
		this.waterfallColorMap = new int[512];
		for (int i = 0; i < 512; i++) {
			int blue = i <= 255 ? i : 511 - i;
			int red  = i <= 255 ? 0 : i - 256;
			waterfallColorMap[i] = Color.argb(0xff, red, 0, blue);
			Log.d(logtag,"Color["+i+"]= RED:"+red+"  BLUE:"+blue);
		}
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

		// Recreate the shaders:
		this.fftPaint.setShader(new LinearGradient(0, 0, 0, getFFTBaseline(), Color.WHITE, Color.BLUE, Shader.TileMode.MIRROR));

		// Recreate the waterfall bitmaps:
		this.createWaterfallBitmaps(width,height-getFFTBaseline(),getPixelPerWaterfallLine());
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
	 * Returns height (in pixel) of each line in the waterfall plot
	 *
	 * @return number of pixels (in vertical direction) of one line in the waterfall plot
	 */
	private int getPixelPerWaterfallLine() {
		return 3;
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
		drawWaterfall(c, mag);

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
		// these should be configurable later:
		float maxDB = 0;
		float minDB = -50;

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
	 * This method will draw the waterfall plot on the given canvas. It will update the bitmaps in
	 * waterfallBitmaps[] with the data in mag and then draw the bitmaps on the canvas.
	 *
	 * @param c				canvas object to draw on
	 * @param mag			array of magnitude values that represent the fft
	 */
	private void drawWaterfall(Canvas c, double[] mag) {
		// these should be configurable later:
		float maxDB = 0;
		float minDB = -50;

		float dbDiff = maxDB - minDB;
		float scale = this.waterfallColorMap.length / dbDiff;
		float sampleWidth 	= (float) this.width / (float) mag.length;	// Size (in pixel) per one fft sample
		int lineHeight = getPixelPerWaterfallLine();	// Height (in pixel) of one waterfall line

		// update the waterfall
		Canvas newline = new Canvas(waterfallBitmaps[waterfallBitmapTopIndex]);
		float position = 0;
		for (int i = 0; i < mag.length; i++) {
			if(mag[i] <= minDB)
				waterfallLinePaint.setColor(waterfallColorMap[0]);
			else if(mag[i] >= maxDB)
				waterfallLinePaint.setColor(waterfallColorMap[waterfallColorMap.length-1]);
			else
				waterfallLinePaint.setColor(waterfallColorMap[(int)((mag[i]-minDB)*scale)]);
			newline.drawRect(position, 0, position + sampleWidth, lineHeight, waterfallLinePaint);
			position += sampleWidth;
		}

		// draw the bitmaps on the canvas:
		int y0 = getFFTBaseline();
		int dy = lineHeight;
		for (int i = 0; i < waterfallBitmaps.length; i++) {
			int idx = (waterfallBitmapTopIndex + i) % waterfallBitmaps.length;
			c.drawBitmap(waterfallBitmaps[idx], 0, y0+i*dy, waterfallPaint);
		}

		// move the array index (note that we have to decrement in order to do it correctly)
		waterfallBitmapTopIndex--;
		if(waterfallBitmapTopIndex < 0)
			waterfallBitmapTopIndex += waterfallBitmaps.length;
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
		String loadStr = String.format("%3.1f %%", load * 100);
		c.drawText(loadStr,10,30,textPaint);
	}
}
