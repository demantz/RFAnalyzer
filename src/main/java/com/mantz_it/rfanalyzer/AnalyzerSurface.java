package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
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
	private Paint gridPaint = null;			// Paint object to draw the grid bitmaps
	private int width;						// current width (in pixels) of the SurfaceView
	private int height;						// current height (in pixels) of the SurfaceView
	private static final String logtag = "AnalyzerSurface";

	private int[] waterfallColorMap = null;		// Colors used to draw the waterfall plot.
												// idx 0 -> weak signal   idx max -> strong signal
	private Bitmap[] waterfallBitmaps = null;	// Each array element holds one line in the waterfall plot
	private int waterfallBitmapTopIndex = 0;	// Indicates which array index in waterfallBitmaps
												// is the most recent (circular array)

	private Bitmap frequencyGrid = null;		// Grid that is drawn on the surface showing the frequency
	private Bitmap powerGrid = null;			// Grid that is drawn on the surface showing the signal power in dB
	private boolean redrawFrequencyGrid = true;	// Indicates whether the frequency grid has to be redrawn or is still valid
	private boolean redrawPowerGrid = true;	// Indicates whether the frequency grid has to be redrawn or is still valid

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

		// Recreate the grid bitmaps
		// First calculate the height of the frequency grid / width of the powerGrid according to
		// the screen density:
		int gridSize = (int) (75 * getResources().getDisplayMetrics().xdpi/200);
		this.frequencyGrid = Bitmap.createBitmap(width,gridSize, Bitmap.Config.ARGB_8888);
		this.powerGrid = Bitmap.createBitmap(gridSize,getFFTBaseline()-frequencyGrid.getHeight(), Bitmap.Config.ARGB_8888);
		this.redrawFrequencyGrid = true;
		this.redrawPowerGrid = true;

		// Fix the text size:
		this.textPaint.setTextSize((int) (frequencyGrid.getHeight()/2.1));
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
		// these should be configurable later:
		int maxDB = -5;
		int minDB = -35;

		// clear the canvas:
		c.drawColor(Color.BLACK);

		// draw the fft:
		drawFFT(c, mag, minDB, maxDB);

		// draw the waterfall:
		drawWaterfall(c, mag, minDB, maxDB);

		// draw the grid (and some more information):
		drawGrid(c, sampleRate, basebandFrequency, frameRate, load, minDB, maxDB);
	}

	/**
	 * This method will draw the fft onto the given canvas
	 *
	 * @param c				canvas object to draw on
	 * @param mag			array of magnitude values that represent the fft
	 * @param minDB			dB level of the bottom line of the fft
	 * @param maxDB			dB level of the top line of the fft
	 */
	private void drawFFT(Canvas c, double[] mag, int minDB, int maxDB) {
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
	 * @param minDB			dB level of the bottom line of the fft
	 * @param maxDB			dB level of the top line of the fft
	 */
	private void drawWaterfall(Canvas c, double[] mag, int minDB, int maxDB) {
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
	 * @param minDB			dB level of the bottom line of the fft
	 * @param maxDB			dB level of the top line of the fft
	 */
	private void drawGrid(Canvas c, int sampleRate, long basebandFrequency, int frameRate, double load, int minDB, int maxDB) {
		// Performance information:
		c.drawText(frameRate+" FPS",width-120,40,textPaint);
		String loadStr = String.format("%3.1f %%", load * 100);
		c.drawText(loadStr,width-120,70,textPaint);

		// Frequency Grid
		if(redrawFrequencyGrid) {
			redrawFrequencyGrid = false;
			generateFrequencyGrid(sampleRate, basebandFrequency);
		}

		if(redrawPowerGrid) {
			redrawPowerGrid = false;
			generatePowerGrid(minDB, maxDB);
		}

		// Put the bitmaps on the canvas:
		c.drawBitmap(frequencyGrid, 0, getFFTBaseline()-frequencyGrid.getHeight(), gridPaint);
		c.drawBitmap(powerGrid, 0, 0, gridPaint);
	}

	/**
	 * This method will draw the frequency grid into the frequencyGrid bitmap
	 *
	 * @param sampleRate	sampleRate that was used to record the samples used to create the fft
	 * @param basebandFrequency		absolute frequency that should be used as center for the grid
	 */
	private void generateFrequencyGrid(int sampleRate, long basebandFrequency) {
		// Calculate pixel width of a minor tick (100KHz)
		float pixelPerMinorTick = (float) (width / (sampleRate/100000.0));

		// Calculate the frequency at the left most point of the fft:
		long startFrequency = (long) (basebandFrequency - (sampleRate/2.0));

		// Calculate the frequency and position of the first Tick (ticks are every 100KHz)
		long tickFreq = (long) Math.ceil(startFrequency/10000.0) * 10000;
		float tickPos = (float) (pixelPerMinorTick / 100000.0 * (tickFreq-startFrequency));

		// Clear the bitmap
		Canvas c = new Canvas(frequencyGrid);
		c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

		// Draw the ticks
		for (int i = 0; i < sampleRate/100000; i++) {
			float tickHeight = 0;
			if(tickFreq % 1000000 == 0) {
				// Major Tick (1MHZ)
				tickHeight = (float) (frequencyGrid.getHeight() / 2.0);
				// Draw Frequency Text:
				c.drawText("" + tickFreq/1000000, tickPos, (float)(frequencyGrid.getHeight()/2.1), textPaint);
			} else if(tickFreq % 500000 == 0) {
				// Half MHz tick
				tickHeight = (float) (frequencyGrid.getHeight() / 3.0);
			} else {
				// Minor tick
				tickHeight = (float) (frequencyGrid.getHeight() / 4.0);
			}
			c.drawLine(tickPos, frequencyGrid.getHeight(), tickPos, frequencyGrid.getHeight() - tickHeight, textPaint);
			tickFreq += 100000;
			tickPos += pixelPerMinorTick;
		}
	}

	/**
	 * This method will draw the power grid into the powerGrid bitmap
	 *
	 * @param minDB		smallest dB value on the scale
	 * @param maxDB		highest dB value on the scale
	 */
	private void generatePowerGrid(int minDB, int maxDB) {
		// Calculate pixel height of a minor tick (1dB)
		float pixelPerMinorTick = (float) (getFFTBaseline() / (maxDB-minDB));

		// Clear the bitmap
		Canvas c = new Canvas(powerGrid);
		c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

		// Draw the ticks from the top to the bottom
		float tickPos = 0;
		for (int tickDB = maxDB; tickDB > minDB; tickDB--) {
			float tickWidth = 0;
			if(tickDB % 10 == 0) {
				// Major Tick (10dB)
				tickWidth = (float) (powerGrid.getWidth() / 3.0);
				// Draw Frequency Text:
				c.drawText("" + tickDB, (float) (powerGrid.getWidth() / 2.9), tickPos, textPaint);
			} else if(tickDB % 5 == 0) {
				// 5 dB tick
				tickWidth = (float) (powerGrid.getWidth() / 3.5);
			} else {
				// Minor tick
				tickWidth = (float) (powerGrid.getWidth() / 5.0);
			}
			c.drawLine(0, tickPos, tickWidth, tickPos, textPaint);
			tickPos += pixelPerMinorTick;
		}
	}
}
