package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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
public class AnalyzerSurface extends SurfaceView implements SurfaceHolder.Callback, ScaleGestureDetector.OnScaleGestureListener {

	private ScaleGestureDetector scaleGestureDetector = null;

	private Paint defaultPaint = null;		// Paint object to draw bitmaps on the canvas
	private Paint fftPaint = null;			// Paint object to draw the fft lines
	private Paint waterfallLinePaint = null;// Paint object to draw one waterfall pixel
	private Paint textPaint = null;			// Paint object to draw text on the canvas
	private int width;						// current width (in pixels) of the SurfaceView
	private int height;						// current height (in pixels) of the SurfaceView
	private static final String logtag = "AnalyzerSurface";

	private int[] waterfallColorMap = null;		// Colors used to draw the waterfall plot.
												// idx 0 -> weak signal   idx max -> strong signal
	private Bitmap[] waterfallLines = null;		// Each array element holds one line in the waterfall plot
	private int waterfallLinesTopIndex = 0;		// Indicates which array index in waterfallLines is the most recent (circular array)

	private Bitmap fftBitmap = null;			// Bitmap that holds the current fft plot
	private Bitmap waterfallBitmap = null;		// Bitmap that holds the current waterfall plot
	private Bitmap frequencyGrid = null;		// Grid that is drawn on the surface showing the frequency
	private Bitmap powerGrid = null;			// Grid that is drawn on the surface showing the signal power in dB
	private Bitmap infoBitmap = null;			// Bitmap that holds information (framerate, load, ...)

	private long frequency = 0;				// Center frequency of the fft (baseband)
	private int sampleRate = 0;				// Sample Rate of the fft
	private int minDB = -35;				// Lowest dB on the scale
	private int maxDB = -5;					// Highest dB on the scale

	private float sizeFactor = 1.5f;		// This factor indicates how much larger the Bitmaps of fft, waterfall,... are
											// generated than the actual surface dimensions.
	private float xScale = 2.0f;			// scale factor of the frequency axis (used to scale the bitmaps inside the surface canvas)
	private float yScale = 2.0f;			// scale factor of the dB axis (used to scale the bitmaps inside the surface canvas)
	private float xScaleMin = 1.5f;			// minimum of the xScale factor
	private float yScaleMin = 1.5f;			// minimum of the yScale factor
	private int xOffset = 200;				// offset (in px) of the fft and waterfall bitmaps in x (frequency) direction
	private int yOffset = 100;				// offset (in px) of the fft bitmap in y (dB) direction

	/**
	 * Constructor. Will initialize the Paint instances and register the callback
	 * functions of the SurfaceHolder
	 *
	 * @param context
	 */
	public AnalyzerSurface(Context context) {
		super(context);
		this.defaultPaint = new Paint();
		this.fftPaint = new Paint();
		this.fftPaint.setColor(Color.BLUE);
		this.fftPaint.setStyle(Paint.Style.FILL);
		this.textPaint = new Paint();
		this.textPaint.setColor(Color.WHITE);
		this.waterfallLinePaint = new Paint();

		// Add a Callback to get informed when the dimensions of the SurfaceView changes:
		this.getHolder().addCallback(this);

		// Create the color map for the waterfall plot (should be customizable later)
		this.createWaterfallColorMap();

		// Instantiate the gesture detector:
		this.scaleGestureDetector = new ScaleGestureDetector(context, this);
	}

	/**
	 * Sets the sample rate, that is used to draw the frequency grid.
	 *
	 * @param sampleRate	new sample rate (in Sps)
	 */
	public void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
		if(this.frequencyGrid != null)
			this.drawFrequencyGrid();
	}

	/**
	 * Sets the center frequency, that is used to draw the frequency grid.
	 *
	 * @param frequency		new baseband frequency (in Hz)
	 */
	public void setFrequency(long frequency) {
		this.frequency = frequency;
		if(this.frequencyGrid != null)
			this.drawFrequencyGrid();
	}

	/**
	 * Sets the power range (minDB and maxDB on the scale)
	 *
	 * @param minDB		new lowest dB value on the scale
	 * @param maxDB		new highest dB value on the scale
	 */
	public void setDBScale(int minDB, int maxDB) {
		this.minDB = minDB;
		this.maxDB = maxDB;
		if(this.powerGrid != null)
			this.drawPowerGrid();
	}

	/**
	 * Will initialize the waterfallLines array for the given width and height of the waterfall plot.
	 * If the array is not null, it will be recycled first.
	 *
	 * @param width				width (in pixel) of the waterfall plot
	 * @param height			height (in pixel) of the waterfall plot (must be multiple of pixelPerLine)
	 * @param pixelPerLine		height (in pixel) of one line in the waterfall plot
	 */
	private void createWaterfallLineBitmaps(int width, int height, int pixelPerLine) {
//		// check if height is multiple of pixelPerLine:
//		if(height % pixelPerLine != 0)
//			throw new IllegalArgumentException("Height (" + height + ") must be multiple of PixelPerLine ("+pixelPerLine+")!");

		// Recycle bitmaps if not null:
		if(this.waterfallLines != null) {
			for(Bitmap b: this.waterfallLines)
				b.recycle();
		}

		// Create new array:
		this.waterfallLinesTopIndex = 0;
		this.waterfallLines = new Bitmap[height/pixelPerLine];
		for (int i = 0; i < waterfallLines.length; i++)
			waterfallLines[i] = Bitmap.createBitmap(width,pixelPerLine, Bitmap.Config.ARGB_8888);
	}

	/**
	 * Will populate the waterfallColorMap array with color instances
	 */
	private void createWaterfallColorMap() {
		this.waterfallColorMap = new int[512];
		for (int i = 0; i < 512; i++) {
			int blue = i <= 255 ? i : 511 - i;
			int red  = i <= 255 ? 0 : i - 256;
			waterfallColorMap[i] = Color.argb(0xff, red, 0, blue);
		}
	}

//------------------- <SurfaceHolder.Callback> ------------------------------//
	/**
	 * SurfaceHolder.Callback function. Gets called when the surface view is created.
	 * We do all the work in surfaceChanged()...
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceCreated(SurfaceHolder holder) {

	}

	/**
	 * SurfaceHolder.Callback function. This is called every time the dimension changes
	 * (and after the SurfaceView is created).
	 *
	 * The main task of this function is to (re-)create all Bitmaps which are depending on
	 * the dimensions of the surface. Bitmaps will be 1,5 times larger than the surface to
	 * enable scrolling.
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		AnalyzerSurface.this.width = width;
		AnalyzerSurface.this.height = height;

		// Recreate the fft plot bitmap.
		this.fftBitmap = Bitmap.createBitmap((int)(width*1.5), (int)(getFFTBaseline()*1.5), Bitmap.Config.ARGB_8888);

		// Recreate the waterfall plot bitmap.
		this.waterfallBitmap = Bitmap.createBitmap((int)(width*1.5), height-getFFTBaseline(), Bitmap.Config.ARGB_8888);

		// Recreate the grid bitmaps
		// First calculate the height of the frequency grid / width of the powerGrid according to
		// the screen density:
		int gridSize = (int) (75 * getResources().getDisplayMetrics().xdpi/200);
		this.frequencyGrid = Bitmap.createBitmap(fftBitmap.getWidth(),gridSize, Bitmap.Config.ARGB_8888);
		this.powerGrid = Bitmap.createBitmap(gridSize,fftBitmap.getHeight(), Bitmap.Config.ARGB_8888);

		// Recreate the info bitmap:
		this.infoBitmap = Bitmap.createBitmap((int)(width*0.2), (int)(height*0.2), Bitmap.Config.ARGB_8888);

		// Recreate the shaders:
		this.fftPaint.setShader(new LinearGradient(0, 0, 0, fftBitmap.getHeight(), Color.WHITE, Color.BLUE, Shader.TileMode.MIRROR));

		// Recreate the waterfall bitmaps:
		this.createWaterfallLineBitmaps(waterfallBitmap.getWidth(), height - getFFTBaseline(), getPixelPerWaterfallLine());

		// Fix the text size:
		this.textPaint.setTextSize((int) (frequencyGrid.getHeight()/2.1));

		// Redraw the grids:
		this.drawFrequencyGrid();
		this.drawPowerGrid();
	}

	/**
	 * SurfaceHolder.Callback function. Gets called before the surface view is destroyed
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {

	}
//------------------- </SurfaceHolder.Callback> ------------------------------//

//------------------- <OnScaleGestureListener> ------------------------------//
	@Override
	public boolean onScale(ScaleGestureDetector detector) {
		//Log.i(logtag,"SCALE: x=" + detector.getCurrentSpanX()/detector.getPreviousSpanX() + "  y=" + detector.getCurrentSpanY()/detector.getPreviousSpanY());
		xScale *= detector.getCurrentSpanX()/detector.getPreviousSpanX();
		yScale *= detector.getCurrentSpanY()/detector.getPreviousSpanY();
		draw();
		return true;
	}

	@Override
	public boolean onScaleBegin(ScaleGestureDetector detector) {
		return true;
	}

	@Override
	public void onScaleEnd(ScaleGestureDetector detector) {
	}
//------------------- </OnScaleGestureListener> ------------------------------//


	@Override
	public boolean onTouchEvent(MotionEvent event) {
		this.scaleGestureDetector.onTouchEvent(event);
		return true;
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
	 * Will update the data set in this surface (fft data, additional information),
	 * then generate new bitmaps and call draw() to refresh the surface.
	 */
	public void update(double[] mag, int frameRate, double load) {
		// these should be configurable later:
		int maxDB = -5;
		int minDB = -35;

		// draw the fft:
		drawFFT(mag);

		// draw the waterfall:
		drawWaterfall(mag);

		// draw the performance information
		drawInfoBitmap(frameRate,load);

		// redraw the surface:
		this.draw();
	}

	/**
	 * Will (re-)draw the current data set on the surface.
	 */
	private void draw() {
		Canvas c = null;
		try {
			c = this.getHolder().lockCanvas();

			synchronized (this.getHolder()) {
				if(c != null) {
					// clear the canvas:
					c.drawColor(Color.BLACK);

					c.drawBitmap(fftBitmap, 	// Draw the FFT
							new Rect(xOffset,
									yOffset,
									xOffset + (int)(width*sizeFactor/xScale),
									yOffset + (int)(getFFTBaseline()*sizeFactor/yScale)),
							new Rect(0,0,width, getFFTBaseline()),
							defaultPaint);
					c.drawBitmap(waterfallBitmap,
							//new Rect(xOffset, 0, xOffset + (int)(width*sizeFactor/xScale), waterfallBitmap.getHeight()),
							null,
							new Rect(0,getFFTBaseline(),width, height),
							defaultPaint);
					c.drawBitmap(frequencyGrid,
							new Rect(xOffset, 0, xOffset + (int)(width*sizeFactor/xScale), frequencyGrid.getHeight()),
							new Rect(0,getFFTBaseline()-frequencyGrid.getHeight(),width, getFFTBaseline()),
							defaultPaint);
					c.drawBitmap(powerGrid,
							new Rect(0,
									yOffset,
									powerGrid.getWidth(),
									yOffset + (int)((getFFTBaseline()-frequencyGrid.getHeight())*sizeFactor/yScale)),
							new Rect(0,0,powerGrid.getWidth(), getFFTBaseline()-frequencyGrid.getHeight()), defaultPaint);
					c.drawBitmap(infoBitmap, width-infoBitmap.getWidth(),10, defaultPaint);
				} else
					Log.d(logtag, "draw: Canvas is null.");
			}
		} catch (Exception e)
		{
			Log.e(logtag,"draw: Error while drawing on the canvas. Stop!");
			e.printStackTrace();
		} finally {
			if (c != null) {
				this.getHolder().unlockCanvasAndPost(c);
			}
		}
	}

	/**
	 * This method will draw the fft onto the fftBitmap
	 *
	 * @param mag			array of magnitude values that represent the fft
	 */
	private void drawFFT(double[] mag) {
		Canvas c = new Canvas(fftBitmap);
		float sampleWidth 	= (float) fftBitmap.getWidth() / (float) mag.length;		// Size (in pixel) per one fft sample
		float dbWidth 		= fftBitmap.getHeight() / (float) Math.abs(maxDB - minDB); 	// Size (in pixel) per 1dB

		// Clear the bitmap:
		c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

		float position = 0;
		for (int i = 0; i < mag.length; i++) {
			if(mag[i] > minDB) {
				float topPixel = (float) (fftBitmap.getHeight() - (mag[i] - minDB) * dbWidth);
				if(topPixel < 0 ) topPixel = 0;
				c.drawRect(position, topPixel, position + sampleWidth, fftBitmap.getHeight(), fftPaint);
			}
			position += sampleWidth;
		}
	}

	/**
	 * This method will draw the waterfall plot onto the waterfallBitmap. It will update the bitmaps in
	 * waterfallLines[] with the data in mag and then draw the bitmaps onto the waterfallBitmap.
	 *
	 * @param mag			array of magnitude values that represent the fft
	 */
	private void drawWaterfall(double[] mag) {
		Canvas c = new Canvas(waterfallBitmap);
		float dbDiff = maxDB - minDB;
		float scale = this.waterfallColorMap.length / dbDiff;
		float sampleWidth 	= (float) waterfallBitmap.getWidth() / (float) mag.length;	// Size (in pixel) per one fft sample
		int lineHeight = getPixelPerWaterfallLine();	// Height (in pixel) of one waterfall line

		// Clear the bitmap:
		c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

		// update the waterfall lines
		Canvas newline = new Canvas(waterfallLines[waterfallLinesTopIndex]);
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
		for (int i = 0; i < waterfallLines.length; i++) {
			int idx = (waterfallLinesTopIndex + i) % waterfallLines.length;
			c.drawBitmap(waterfallLines[idx], 0, i*lineHeight, defaultPaint);
		}

		// move the array index (note that we have to decrement in order to do it correctly)
		waterfallLinesTopIndex--;
		if(waterfallLinesTopIndex < 0)
			waterfallLinesTopIndex += waterfallLines.length;
	}

	/**
	 * This method will draw the frequency grid into the frequencyGrid bitmap
	 */
	private void drawFrequencyGrid() {
		// Calculate pixel width of a minor tick (100KHz)
		float pixelPerMinorTick = (float) (frequencyGrid.getWidth() / (sampleRate/100000.0));

		// Calculate the frequency at the left most point of the fft:
		long startFrequency = (long) (frequency - (sampleRate/2.0));

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
	 */
	private void drawPowerGrid() {
		// Calculate pixel height of a minor tick (1dB)
		float pixelPerMinorTick = (float) (powerGrid.getHeight() / (maxDB-minDB));

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

	/**
	 * This method will draw the performance information into the info bitmap
	 */
	private void drawInfoBitmap(int frameRate, double load) {
		Canvas c = new Canvas(infoBitmap);
		float textHeight = textPaint.getTextSize();

		// Clear the bitmap
		c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

		// Draw the FFT/s rate
		c.drawText(frameRate+" FPS",0,textHeight,textPaint);

		// Draw the load
		String loadStr = String.format("%3.1f %%", load * 100);
		c.drawText(loadStr,0,2*textHeight,textPaint);
	}
}
