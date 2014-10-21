package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.Log;
import android.view.GestureDetector;
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
public class AnalyzerSurface extends SurfaceView implements SurfaceHolder.Callback,
															ScaleGestureDetector.OnScaleGestureListener,
															GestureDetector.OnGestureListener,
															GestureDetector.OnDoubleTapListener {

	// Gesture detectors to detect scaling, scrolling double tapping, ...
	private ScaleGestureDetector scaleGestureDetector = null;
	private GestureDetector gestureDetector = null;

	private IQSourceInterface source = null;	// Reference to the IQ source for tuning and retrieving properties

	private Paint defaultPaint = null;		// Paint object to draw bitmaps on the canvas
	private Paint blackPaint = null;		// Paint object to draw black (erase)
	private Paint fftPaint = null;			// Paint object to draw the fft lines
	private Paint waterfallLinePaint = null;// Paint object to draw one waterfall pixel
	private Paint textPaint = null;			// Paint object to draw text on the canvas
	private int width;						// current width (in pixels) of the SurfaceView
	private int height;						// current height (in pixels) of the SurfaceView
	private boolean doAutoscaleInNextDraw = false;	// will cause draw() to adjust minDB and maxDB according to the samples
	private boolean verticalZoomEnabled = true;		// Enables vertical zooming (dB scale)
	private boolean verticalScrollEnabled = true;	// Enables vertical scrolling (dB scale)

	private static final String LOGTAG = "AnalyzerSurface";
	private static final int MIN_DB = -100;	// Smallest dB value the vertical scale can start
	private static final int MAX_DB = 10;	// Highest dB value the vertical scale can start
	private static final int MIN_VIRTUAL_SAMPLERATE = 64;	// Smallest virtual sample rate

	private int[] waterfallColorMap = null;		// Colors used to draw the waterfall plot.
												// idx 0 -> weak signal   idx max -> strong signal
	private Bitmap[] waterfallLines = null;		// Each array element holds one line in the waterfall plot
	private int waterfallLinesTopIndex = 0;		// Indicates which array index in waterfallLines is the most recent (circular array)

	// virtual frequency and sample rate indicate the current visible viewport of the fft. they vary from
	// the actual values when the user does scrolling and zooming
	private long virtualFrequency = -1;		// Center frequency of the fft (baseband) AS SHOWN ON SCREEN
	private int virtualSampleRate = -1;		// Sample Rate of the fft AS SHOWN ON SCREEN
	private float minDB = -35;				// Lowest dB on the scale
	private float maxDB = -5;					// Highest dB on the scale

	private float fftRatio = 0.5f;					// percentage of the height the fft consumes on the surface
	private float waterfallRatio = 1 - fftRatio;	// percentage of the height the waterfall consumes on the surface

	/**
	 * Constructor. Will initialize the Paint instances and register the callback
	 * functions of the SurfaceHolder
	 *
	 * @param context
	 */
	public AnalyzerSurface(Context context) {
		super(context);
		this.defaultPaint = new Paint();
		this.blackPaint = new Paint();
		this.blackPaint.setColor(Color.BLACK);
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
		this.gestureDetector = new GestureDetector(context, this);
	}

	/**
	 * Set the source attribute of the analyzer view.
	 * Parameters like max. sample rate, ... are derived from the source instance. It will
	 * also be used to set sample rate and frequency on double tap.
	 *
	 * @param source	IQSource instance
	 */
	public void setSource(IQSourceInterface source) {
		if(source == null)
			return;

		this.source = source;
		this.virtualFrequency = source.getFrequency();
		this.virtualSampleRate = source.getSampleRate();
	}

	/**
	 * Sets the power range (minDB and maxDB on the scale).
	 * Note: we have to make sure this is an atomic operation to not interfere with the
	 * processing/drawing thread.
	 *
	 * @param minDB		new lowest dB value on the scale
	 * @param maxDB		new highest dB value on the scale
	 */
	public void setDBScale(float minDB, float maxDB) {
		synchronized (this.getHolder()) {
			this.minDB = minDB;
			this.maxDB = maxDB;
		}
	}

	/**
	 * Will cause the surface to automatically adjust the dB scale at the
	 * next call of draw() so that it fits the incoming fft samples perfectly
	 */
	public void autoscale() {
		this.doAutoscaleInNextDraw = true;
	}

	/**
	 * Will enable/disable the vertical scrolling (dB scale)
	 *
	 * @param enable	true for scrolling enabled; false for disabled
	 */
	public void setVerticalScrollEnabled(boolean enable) {
		this.verticalScrollEnabled = enable;
	}

	/**
	 * Will enable/disable the vertical zooming (dB scale)
	 *
	 * @param enable	true for zooming enabled; false for disabled
	 */
	public void setVerticalZoomEnabled(boolean enable) {
		this.verticalZoomEnabled = enable;
	}

	/**
	 * Will move the frequency scale so that the given frequency is centered
	 *
	 * @param frequency		frequency that should be centered on the screen
	 */
	public void setVirtualFrequency(long frequency) {
		this.virtualFrequency = frequency;
	}

	/**
	 * Will scale the frequency scale so that the given bandwidth is shown
	 *
	 * @param sampleRate	sample rate / bandwidth to show on the screen
	 */
	public void setVirtualSampleRate(int sampleRate) {
		this.virtualSampleRate = sampleRate;
	}

	/**
	 * @return		The center frequency as shown on the screen
	 */
	public long getVirtualFrequency() {
		return virtualFrequency;
	}

	/**
	 * @return		The sample rate as shown on the screen
	 */
	public int getVirtualSampleRate() {
		return virtualSampleRate;
	}

	/**
	 * @return		The lowest dB value on the vertical scale
	 */
	public float getMinDB() {
		return minDB;
	}

	/**
	 * @return		The highest dB value on the vertical scale
	 */
	public float getMaxDB() {
		return maxDB;
	}

	/**
	 * Will initialize the waterfallLines array for the given width and height of the waterfall plot.
	 * If the array is not null, it will be recycled first.
	 */
	private void createWaterfallLineBitmaps() {
		// Recycle bitmaps if not null:
		if(this.waterfallLines != null) {
			for(Bitmap b: this.waterfallLines)
				b.recycle();
		}

		// Create new array:
		this.waterfallLinesTopIndex = 0;
		this.waterfallLines = new Bitmap[getWaterfallHeight()/getPixelPerWaterfallLine()];
		for (int i = 0; i < waterfallLines.length; i++)
			waterfallLines[i] = Bitmap.createBitmap(width,getPixelPerWaterfallLine(), Bitmap.Config.ARGB_8888);
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
	 * @param holder	reference to the surface holder
	 * @param format
	 * @param width		current width of the surface view
	 * @param height	current height of the surface view
	 */
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		if(this.width != width || this.height != height) {
			this.width = width;
			this.height = height;

			// Recreate the shaders:
			this.fftPaint.setShader(new LinearGradient(0, 0, 0, getFftHeight(), Color.WHITE, Color.BLUE, Shader.TileMode.MIRROR));

			// Recreate the waterfall bitmaps:
			this.createWaterfallLineBitmaps();

			// Fix the text size:
			this.textPaint.setTextSize((int) (getGridSize() / 2.1));
		}
	}

	/**
	 * SurfaceHolder.Callback function. Gets called before the surface view is destroyed
	 *
	 * @param holder	reference to the surface holder
	 */
	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {

	}
//------------------- </SurfaceHolder.Callback> -----------------------------//

//------------------- <OnScaleGestureListener> ------------------------------//
	@Override
	public boolean onScale(ScaleGestureDetector detector) {
		if(source != null) {
			float xScale = detector.getCurrentSpanX() / detector.getPreviousSpanX();
			long frequencyFocus = virtualFrequency + (int) ((detector.getFocusX() / width - 0.5) * virtualSampleRate);
			virtualSampleRate = (int) Math.min(Math.max(virtualSampleRate / xScale, MIN_VIRTUAL_SAMPLERATE), source.getMaxSampleRate());
			virtualFrequency = Math.min(Math.max(frequencyFocus + (long) ((virtualFrequency - frequencyFocus) / xScale),
					source.getMinFrequency() - source.getSampleRate() / 2), source.getMaxFrequency() + source.getSampleRate() / 2);

			if (verticalZoomEnabled) {
				float yScale = detector.getCurrentSpanY() / detector.getPreviousSpanY();
				float dBFocus = maxDB - (maxDB - minDB) * (detector.getFocusY() / getFftHeight());
				float newMinDB = Math.min(Math.max(dBFocus - (dBFocus - minDB) / yScale, MIN_DB), MAX_DB - 10);
				float newMaxDB = Math.min(Math.max(dBFocus - (dBFocus - maxDB) / yScale, newMinDB + 10), MAX_DB);
				this.setDBScale(newMinDB, newMaxDB);
			}
		}

		return true;
	}

	@Override
	public boolean onScaleBegin(ScaleGestureDetector detector) {
		return true;
	}

	@Override
	public void onScaleEnd(ScaleGestureDetector detector) {
	}
//------------------- </OnScaleGestureListener> -----------------------------//

//------------------- <OnGestureListener> -----------------------------------//
	@Override
	public boolean onDown(MotionEvent e) {
		return true;	// not used
	}

	@Override
	public void onShowPress(MotionEvent e) {
		// not used
	}

	@Override
	public boolean onSingleTapUp(MotionEvent e) {
		return true;	// not used
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		if (source != null) {
			virtualFrequency = Math.min(Math.max(virtualFrequency + (long) (((virtualSampleRate / (float) width) * distanceX)),
					source.getMinFrequency() - source.getSampleRate() / 2), source.getMaxFrequency() + source.getSampleRate() / 2);

			if(virtualFrequency <= 0)
				virtualFrequency = 1;

			if (verticalScrollEnabled) {
				float yDiff = (maxDB - minDB) * (distanceY / (float) getFftHeight());
				// Make sure we stay in the boundaries:
				if (maxDB - yDiff > MAX_DB)
					yDiff = MAX_DB - maxDB;
				if (minDB - yDiff < MIN_DB)
					yDiff = MIN_DB - minDB;
				this.setDBScale(minDB - yDiff, maxDB - yDiff);
			}
		}

		return true;
	}

	@Override
	public void onLongPress(MotionEvent e) {
		// not used
	}

	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
		return true;
	}
//------------------- </OnGestureListener> ----------------------------------//

//------------------- <OnDoubleTapListener> ---------------------------------//
	@Override
	public boolean onSingleTapConfirmed(MotionEvent e) {
		return true;	// not used
	}

	@Override
	public boolean onDoubleTap(MotionEvent e) {
		if(source != null
				&& virtualFrequency >= source.getMinFrequency()
				&& virtualFrequency <= source.getMaxFrequency()
				&& virtualSampleRate >= source.getMinSampleRate()
				&& virtualSampleRate <= source.getMaxSampleRate()) {
			source.setFrequency(virtualFrequency);
			source.setSampleRate(virtualSampleRate);
		} else
			Log.e(LOGTAG,"onDoubleTap: Source is not set or out of range!");
		return true;
	}

	@Override
	public boolean onDoubleTapEvent(MotionEvent e) {
		return true;	// not used
	}
//------------------- </OnDoubleTapListener> --------------------------------//

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		boolean retVal = this.scaleGestureDetector.onTouchEvent(event);
		retVal = this.gestureDetector.onTouchEvent(event) || retVal;
		return retVal;
	}


	/**
	 * Returns the height of the fft plot in px (y coordinate of the bottom line of the fft spectrum)
	 *
	 * @return heigth (in px) of the fft
	 */
	private int getFftHeight() {
		return (int) (height * fftRatio);
	}

	/**
	 * Returns the height of the waterfall plot in px
	 *
	 * @return heigth (in px) of the waterfall
	 */
	private int getWaterfallHeight() {
		return (int) (height * waterfallRatio);
	}

	/**
	 * Returns the height/width of the frequency/power grid in px
	 *
	 * @return size of the grid (frequency grid height / power grid width) in px
	 */
	private int getGridSize() {
		return (int) (75 * getResources().getDisplayMetrics().xdpi/200);
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
	 * Will (re-)draw the given data set on the surface. Note that it actually only draws
	 * a sub set of the fft data depending on the current settings of virtual frequency and sample rate.
	 *
	 * @param mag			array of magnitude values that represent the fft
	 * @param frequency		center frequency
	 * @param sampleRate	sample rate
	 * @param frameRate 	current frame rate (FPS)
	 * @param load			current load (percentage [0..1])
	 */
	public void draw(double[] mag, long frequency, int sampleRate, int frameRate, double load) {

		if(virtualFrequency < 0)
			virtualFrequency = frequency;
		if(virtualSampleRate < 0)
			virtualSampleRate = sampleRate;

		// Calculate the start and end index to draw mag according to frequency and sample rate and
		// the virtual frequency and sample rate:
		float samplesPerHz = (float) mag.length/ (float) sampleRate;	// indicates how many samples in mag cover 1 Hz
		long frequencyDiff = virtualFrequency - frequency;				// difference between center frequencies
		int sampleRateDiff = virtualSampleRate - sampleRate;			// difference between sample rates
		int start = (int)((frequencyDiff - sampleRateDiff/2.0) * samplesPerHz);
		int end = mag.length + (int)((frequencyDiff + sampleRateDiff/2.0) * samplesPerHz);

		// Autoscale
		if(doAutoscaleInNextDraw) {
			doAutoscaleInNextDraw = false;
			float min = MAX_DB;
			float max = MIN_DB;
			for(int i = Math.max(0,start); i < Math.min(mag.length,end); i++) {
				// try to avoid the DC peak (which is always exactly in the middle of mag:
				if(i == (mag.length/2)-5)
					i+=10;	// This effectively skips the DC offset peak
				min = Math.min((float)mag[i], min);
				max = Math.max((float)mag[i], max);
			}
			if(min<max){
				minDB = Math.max(min, MIN_DB);
				maxDB = Math.min(max, MAX_DB);
			}
		}

		// Draw:
		Canvas c = null;
		try {
			c = this.getHolder().lockCanvas();

			synchronized (this.getHolder()) {
				if(c != null) {
					// Draw all the components
					drawFFT(c, mag, start, end);
					drawWaterfall(c);
					drawFrequencyGrid(c);
					drawPowerGrid(c);
					drawPerformanceInfo(c, frameRate, load);
				} else
					Log.d(LOGTAG, "draw: Canvas is null.");
			}
		} catch (Exception e)
		{
			Log.e(LOGTAG,"draw: Error while drawing on the canvas. Stop!");
			e.printStackTrace();
		} finally {
			if (c != null) {
				this.getHolder().unlockCanvasAndPost(c);
			}
		}
	}

	/**
	 * This method will draw the fft onto the canvas. It will also update the bitmap in
	 * waterfallLines[waterfallLinesTopIndex] with the data from mag.
	 * Important: start and end may be out of bounds of the mag array. This will cause black
	 * padding.
	 *
	 * @param c			canvas of the surface view
	 * @param mag		array of magnitude values that represent the fft
	 * @param start		first index to draw from mag (may be negative)
	 * @param end		last index to draw from mag (may be > mag.length)
	 */
	private void drawFFT(Canvas c, double[] mag, int start, int end) {
		float sampleWidth 	= (float) width / (float) (end-start);		// Size (in pixel) per one fft sample
		float dbDiff 		= maxDB - minDB;
		float dbWidth 		= getFftHeight() / dbDiff; 	// Size (in pixel) per 1dB in the fft
		float scale 		= this.waterfallColorMap.length / dbDiff;	// scale for the color mapping of the waterfall

		// Get a canvas from the bitmap of the current waterfall line and clear it:
		Canvas newline = new Canvas(waterfallLines[waterfallLinesTopIndex]);
		newline.drawColor(Color.BLACK);

		// Clear the fft area in the canvas:
		c.drawRect(0, 0, width, getFftHeight(), blackPaint);

		// The start position to draw is either 0 or greater 0, if start is negative:
		float position = start>=0 ? 0 : sampleWidth * start * -1;

		// Draw sample by sample:
		for (int i = Math.max(start,0); i < mag.length; i++) {
			// FFT:
			if(mag[i] > minDB) {
				float topPixel = (float) (getFftHeight() - (mag[i] - minDB) * dbWidth);
				if(topPixel < 0 ) topPixel = 0;
				c.drawRect(position, topPixel, position + sampleWidth, getFftHeight(), fftPaint);
			}

			// Waterfall:
			if(mag[i] <= minDB)
				waterfallLinePaint.setColor(waterfallColorMap[0]);
			else if(mag[i] >= maxDB)
				waterfallLinePaint.setColor(waterfallColorMap[waterfallColorMap.length-1]);
			else
				waterfallLinePaint.setColor(waterfallColorMap[(int)((mag[i]-minDB)*scale)]);
			newline.drawRect(position, 0, position + sampleWidth, getPixelPerWaterfallLine(), waterfallLinePaint);

			// Shift position:
			position += sampleWidth;
		}
	}

	/**
	 * This method will draw the waterfall plot onto the canvas.
	 *
	 * @param c			canvas of the surface view
	 */
	private void drawWaterfall(Canvas c) {
		// draw the bitmaps on the canvas:
		for (int i = 0; i < waterfallLines.length; i++) {
			int idx = (waterfallLinesTopIndex + i) % waterfallLines.length;
			c.drawBitmap(waterfallLines[idx], 0, getFftHeight() + i*getPixelPerWaterfallLine(), defaultPaint);
		}

		// move the array index (note that we have to decrement in order to do it correctly)
		waterfallLinesTopIndex--;
		if(waterfallLinesTopIndex < 0)
			waterfallLinesTopIndex += waterfallLines.length;
	}

	/**
	 * This method will draw the frequency grid into the canvas
	 *
	 * @param c				canvas of the surface view
	 */
	private void drawFrequencyGrid(Canvas c) {
		String frequencyStr;
		double MHZ = 1000000F;
		double tickFreqMHz;
		float lastTextEndPos = -99999;	// will indicate the horizontal pixel pos where the last text ended
		float textPos;

		// Calculate the min space (in px) between text if we want it separated by at least
		// the same space as two dashes would consume.
		Rect bounds = new Rect();
		textPaint.getTextBounds("--",0 , 2, bounds);
		float minFreeSpaceBetweenText = bounds.width();

		// Calculate span of a minor tick (must be a power of 10KHz)
		int tickSize = 10;	// we start with 10KHz
		float helperVar = virtualSampleRate / 20f;
		while(helperVar > 100) {
			helperVar = helperVar / 10f;
			tickSize = tickSize * 10;
		}

		// Calculate pixel width of a minor tick
		float pixelPerMinorTick = width / (virtualSampleRate/(float)tickSize);

		// Calculate the frequency at the left most point of the fft:
		long startFrequency = (long) (virtualFrequency - (virtualSampleRate/2.0));

		// Calculate the frequency and position of the first Tick (ticks are every <tickSize> KHz)
		long tickFreq = (long) (Math.ceil((double) startFrequency/(float)tickSize) * tickSize);
		float tickPos = pixelPerMinorTick / (float) tickSize * (tickFreq-startFrequency);

		// Draw the ticks
		for (int i = 0; i < virtualSampleRate/(float)tickSize; i++) {
			float tickHeight;
			if(tickFreq % (tickSize*10) == 0) {
				// Major Tick (10x <tickSize> KHz)
				tickHeight = (float) (getGridSize() / 2.0);

				// Draw Frequency Text (always in MHz)
				tickFreqMHz = tickFreq/MHZ;
				if(tickFreqMHz == (int) tickFreqMHz)
					frequencyStr = String.format("%d", (int)tickFreqMHz);
				else
					frequencyStr = String.format("%s", tickFreqMHz);
				textPaint.getTextBounds(frequencyStr, 0, frequencyStr.length(), bounds);
				textPos = tickPos - bounds.width()/2;

				// ...only if not overlapping with the last text:
				if(lastTextEndPos+minFreeSpaceBetweenText < textPos) {
					c.drawText(frequencyStr, textPos, getFftHeight() - tickHeight, textPaint);
					lastTextEndPos = textPos + bounds.width();
				}
			} else if(tickFreq % (tickSize*5) == 0) {
				// Half major tick (5x <tickSize> KHz)
				tickHeight = (float) (getGridSize() / 3.0);

				// Draw Frequency Text (always in MHz)...
				tickFreqMHz = tickFreq / MHZ;
				if (tickFreqMHz == (int) tickFreqMHz)
					frequencyStr = String.format("%d", (int) tickFreqMHz);
				else
					frequencyStr = String.format("%s", tickFreqMHz);
				textPaint.getTextBounds(frequencyStr, 0, frequencyStr.length(), bounds);
				textPos = tickPos - bounds.width()/2;

				// ...only if not overlapping with the last text:
				if(lastTextEndPos+minFreeSpaceBetweenText < textPos) {
					// ... if enough space between the major ticks:
					if (bounds.width() < pixelPerMinorTick * 4) {
						c.drawText(frequencyStr, textPos, getFftHeight() - tickHeight, textPaint);
						lastTextEndPos = textPos + bounds.width();
					}
				}
			} else {
				// Minor tick (<tickSize> KHz)
				tickHeight = (float) (getGridSize() / 4.0);
			}

			// Draw the tick line:
			c.drawLine(tickPos, getFftHeight(), tickPos, getFftHeight() - tickHeight, textPaint);
			tickFreq += tickSize;
			tickPos += pixelPerMinorTick;
		}
	}

	/**
	 * This method will draw the power grid into the canvas
	 *
	 * @param c				canvas of the surface view
	 */
	private void drawPowerGrid(Canvas c) {
		// Calculate pixel height of a minor tick (1dB)
		float pixelPerMinorTick = (float) (getFftHeight() / (maxDB-minDB));

		// Draw the ticks from the top to the bottom. Stop as soon as we interfere with the frequency scale
		int tickDB = (int) maxDB;
		float tickPos = (maxDB - tickDB)*pixelPerMinorTick;
		for (; tickDB > minDB; tickDB--) {
			float tickWidth;
			if(tickDB % 10 == 0) {
				// Major Tick (10dB)
				tickWidth = (float) (getGridSize() / 3.0);
				// Draw Frequency Text:
				c.drawText("" + tickDB, (float) (getGridSize() / 2.9), tickPos, textPaint);
			} else if(tickDB % 5 == 0) {
				// 5 dB tick
				tickWidth = (float) (getGridSize() / 3.5);
			} else {
				// Minor tick
				tickWidth = (float) (getGridSize() / 5.0);
			}
			c.drawLine(0, tickPos, tickWidth, tickPos, textPaint);
			tickPos += pixelPerMinorTick;

			// stop if we interfere with the frequency grid:
			if (tickPos > getFftHeight() - getGridSize())
				break;
		}
	}

	/**
	 * This method will draw the performance information into the canvas
	 *
	 * @param c				canvas of the surface view
	 * @param frameRate 	current frame rate (FPS)
	 * @param load			current load (percentage [0..1])
	 */
	private void drawPerformanceInfo(Canvas c, int frameRate, double load) {
		Rect bounds = new Rect();
		String text;

		// Draw the FFT/s rate
		text = frameRate+" FPS";
		textPaint.getTextBounds(text,0 , text.length(), bounds);
		c.drawText(text,width-bounds.width(),bounds.height(), textPaint);

		// Draw the load
		text = String.format("%3.1f %%", load * 100);
		textPaint.getTextBounds(text,0 , text.length(), bounds);
		c.drawText(text,width-bounds.width(),bounds.height() * 2,textPaint);
	}
}
