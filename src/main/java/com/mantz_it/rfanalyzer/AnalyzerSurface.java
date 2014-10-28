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
															GestureDetector.OnGestureListener {

	// Gesture detectors to detect scaling, scrolling ...
	private ScaleGestureDetector scaleGestureDetector = null;
	private GestureDetector gestureDetector = null;

	private IQSourceInterface source = null;			// Reference to the IQ source for tuning and retrieving properties
	private CallbackInterface callbackHandler = null;	// Reference to a callback handler

	private Paint defaultPaint = null;		// Paint object to draw bitmaps on the canvas
	private Paint blackPaint = null;		// Paint object to draw black (erase)
	private Paint fftPaint = null;			// Paint object to draw the fft lines
	private Paint peakHoldPaint = null;		// Paint object to draw the fft peak hold points
	private Paint waterfallLinePaint = null;// Paint object to draw one waterfall pixel
	private Paint textPaint = null;			// Paint object to draw text on the canvas
	private Paint demodSelectorPaint = null;// Paint object to draw the area of the channel
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
	private int waterfallColorMapType = COLORMAP_GQRX;
	public static final int COLORMAP_JET = 1;		// BLUE(0,0,1) - LIGHT_BLUE(0,1,1) - GREEN(0,1,0) - YELLOW(1,1,0) - RED(1,0,0)
	public static final int COLORMAP_HOT = 2;		// BLACK (0,0,0) - RED (1,0,0) - YELLOW (1,1,0) - WHITE (1,1,1)
	public static final int COLORMAP_OLD = 3;		// from version 1.00 :)
	public static final int COLORMAP_GQRX = 4;		// from https://github.com/csete/gqrx  -> qtgui/plotter.cpp

	private int fftDrawingType = FFT_DRAWING_TYPE_LINE;	// Indicates how the fft should be drawn
	public static final int FFT_DRAWING_TYPE_BAR = 1;	// draw as bars
	public static final int FFT_DRAWING_TYPE_LINE = 2;	// draw as line

	private int averageLength = 0;				// indicates whether or not peak hold points should be drawn
	private double[][] historySamples;			// array that holds the last averageLength fft sample packets
	private int oldesthistoryIndex;				// index in historySamples which holds the oldest samples
	private boolean peakHoldEnabled = false;	// indicates whether peak hold should be enabled or disabled
	private double[] peaks;						// peak hold points

	// virtual frequency and sample rate indicate the current visible viewport of the fft. they vary from
	// the actual values when the user does scrolling and zooming
	private long virtualFrequency = -1;		// Center frequency of the fft (baseband) AS SHOWN ON SCREEN
	private int virtualSampleRate = -1;		// Sample Rate of the fft AS SHOWN ON SCREEN
	private float minDB = -35;				// Lowest dB on the scale
	private float maxDB = -5;				// Highest dB on the scale
	private long lastFrequency;				// Center frequency of the last packet of fft samples
	private int lastSampleRate;				// Sample rate of the last packet of fft samples

	private boolean demodulationEnabled = false;	// indicates whether demodulation is enabled or disabled
	private long channelFrequency = -1;				// center frequency of the demodulator
	private int channelWidth = 50000;				// (half) width of the channel filter of the demodulator
	private float squelch = -1;						// squelch threshold in dB

	// scroll type stores the intention of the user on a pointer down event:
	private int scrollType = 0;
	private static final int SCROLLTYPE_NORMAL = 1;
	private static final int SCROLLTYPE_CHANNEL_FREQUENCY = 2;
	private static final int SCROLLTYPE_CHANNEL_WIDTH_LEFT = 3;
	private static final int SCROLLTYPE_CHANNEL_WIDTH_RIGHT = 4;
	private static final int SCROLLTYPE_SQUELCH = 5;

	private float fftRatio = 0.5f;					// percentage of the height the fft consumes on the surface
	private float waterfallRatio = 1 - fftRatio;	// percentage of the height the waterfall consumes on the surface

	/**
	 * Constructor. Will initialize the Paint instances and register the callback
	 * functions of the SurfaceHolder
	 *
	 * @param context
	 */
	public AnalyzerSurface(Context context, CallbackInterface callbackHandler) {
		super(context);
		this.callbackHandler = callbackHandler;
		this.defaultPaint = new Paint();
		this.blackPaint = new Paint();
		this.blackPaint.setColor(Color.BLACK);
		this.fftPaint = new Paint();
		this.fftPaint.setColor(Color.BLUE);
		this.fftPaint.setStyle(Paint.Style.FILL);
		this.peakHoldPaint = new Paint();
		this.peakHoldPaint.setColor(Color.YELLOW);
		this.textPaint = new Paint();
		this.textPaint.setColor(Color.WHITE);
		this.waterfallLinePaint = new Paint();
		this.demodSelectorPaint = new Paint();
		this.demodSelectorPaint.setColor(Color.WHITE);

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
	 * Will create a new color map corresponding to the given typ
	 * @param type		COLORMAP_JET, _HOT, _OLD, _GQRX
	 */
	public void setWaterfallColorMapType(int type) {
		if(this.waterfallColorMapType != type) {
			this.waterfallColorMapType = type;
			this.createWaterfallColorMap();
		}
	}

	/**
	 * @return		The waterfall color map type: COLORMAP_JET, _HOT, _OLD, _GQRX
	 */
	public int getWaterfallColorMapType() {
		return waterfallColorMapType;
	}

	/**
	 * Will change the drawing type of the fft to the given type
	 * @param fftDrawingType	FFT_DRAWING_TYPE_BAR, FFT_DRAWING_TYPE_LINE
	 */
	public void setFftDrawingType(int fftDrawingType) {
		this.fftDrawingType = fftDrawingType;
	}

	/**
	 * Will change the number of history packets used to calculate the average.
	 * @param length	number of history packets; 0 for no averaging
	 */
	public void setAverageLength(int length) {
		this.averageLength = length;
	}

	/**
	 * @param enable	true turns peak hold on; false turns it off
	 */
	public void setPeakHoldEnabled(boolean enable) {
		this.peakHoldEnabled = enable;
	}

	public long getChannelFrequency() {
		return channelFrequency;
	}

	public int getChannelWidth() {
		return channelWidth;
	}

	public float getSquelch() {
		return squelch;
	}

	public void setSquelch(float squelch) {
		this.squelch = squelch;
	}

	public void setChannelWidth(int channelWidth) {
		this.channelWidth = channelWidth;
	}

	public void setChannelFrequency(long channelFrequency) {
		this.channelFrequency = channelFrequency;
	}

	public void setDemodulationEnabled(boolean demodulationEnabled) {
		synchronized (this.getHolder()) {
			if(demodulationEnabled) {
				// set viewport correctly:
				this.virtualSampleRate = (int)(source.getSampleRate() * 0.9);
				source.setFrequency(virtualFrequency);

				// initialize channel freq and squelch:
				this.channelFrequency = virtualFrequency;
				this.squelch = minDB + (maxDB-minDB)/10;
			}
			this.demodulationEnabled = demodulationEnabled;
		}
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
		synchronized (this.getHolder()) {
			switch (this.waterfallColorMapType) {
				case COLORMAP_JET:	// BLUE(0,0,1) - LIGHT_BLUE(0,1,1) - GREEN(0,1,0) - YELLOW(1,1,0) - RED(1,0,0)
					this.waterfallColorMap = new int[256*4];
					for (int i = 0; i < 256; i++) waterfallColorMap[i]     = Color.argb(0xff, 0, i, 255);
					for (int i = 0; i < 256; i++) waterfallColorMap[256+i] = Color.argb(0xff, 0, 255, 255-i);
					for (int i = 0; i < 256; i++) waterfallColorMap[512+i] = Color.argb(0xff, i, 255, 0);
					for (int i = 0; i < 256; i++) waterfallColorMap[768+i] = Color.argb(0xff, 255, 255-i, 0);
					break;
				case COLORMAP_HOT:	// BLACK (0,0,0) - RED (1,0,0) - YELLOW (1,1,0) - WHITE (1,1,1)
					this.waterfallColorMap = new int[256*3];
					for (int i = 0; i < 256; i++) waterfallColorMap[i]     = Color.argb(0xff, i, 0, 0);
					for (int i = 0; i < 256; i++) waterfallColorMap[256+i] = Color.argb(0xff, 255, i, 0);
					for (int i = 0; i < 256; i++) waterfallColorMap[512+i] = Color.argb(0xff, 255, 255, i);
					break;
				case COLORMAP_OLD:
					this.waterfallColorMap = new int[512];
					for (int i = 0; i < 512; i++) {
						int blue = i <= 255 ? i : 511 - i;
						int red = i <= 255 ? 0 : i - 256;
						waterfallColorMap[i] = Color.argb(0xff, red, 0, blue);
					}
					break;
				case COLORMAP_GQRX:
					this.waterfallColorMap = new int[256];
					for (int i = 0; i < 256; i++) {
						if (i < 20) 						waterfallColorMap[i] = Color.argb(0xff,0, 0, 0); // level 0: black background
						else if ((i >= 20) && (i < 70)) 	waterfallColorMap[i] = Color.argb(0xff,0, 0, 140*(i-20)/50); // level 1: black -> blue
						else if ((i >= 70) && (i < 100)) 	waterfallColorMap[i] = Color.argb(0xff,60*(i-70)/30, 125*(i-70)/30, 115*(i-70)/30 + 140); // level 2: blue -> light-blue / greenish
						else if ((i >= 100) && (i < 150)) 	waterfallColorMap[i] = Color.argb(0xff,195*(i-100)/50 + 60, 130*(i-100)/50 + 125, 255-(255*(i-100)/50)); // level 3: light blue -> yellow
						else if ((i >= 150) && (i < 250)) 	waterfallColorMap[i] = Color.argb(0xff,255, 255-255*(i-150)/100, 0); // level 4: yellow -> red
						else if (i >= 250) 					waterfallColorMap[i] = Color.argb(0xff,255, 255*(i-250)/5, 255*(i-250)/5); // level 5: red -> white
					}
					break;
				default:
					Log.e(LOGTAG,"createWaterfallColorMap: Unknown color map type: " + waterfallColorMapType);
			}
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
			int maxSampleRate = demodulationEnabled ? (int)(source.getSampleRate()*0.9) : source.getMaxSampleRate();
			virtualSampleRate = (int) Math.min(Math.max(virtualSampleRate / xScale, MIN_VIRTUAL_SAMPLERATE), maxSampleRate);
			virtualFrequency = Math.min(Math.max(frequencyFocus + (long) ((virtualFrequency - frequencyFocus) / xScale),
					source.getMinFrequency() - source.getSampleRate() / 2), source.getMaxFrequency() + source.getSampleRate() / 2);

			if (verticalZoomEnabled) {
				float yScale = detector.getCurrentSpanY() / detector.getPreviousSpanY();
				float dBFocus = maxDB - (maxDB - minDB) * (detector.getFocusY() / getFftHeight());
				float newMinDB = Math.min(Math.max(dBFocus - (dBFocus - minDB) / yScale, MIN_DB), MAX_DB - 10);
				float newMaxDB = Math.min(Math.max(dBFocus - (dBFocus - maxDB) / yScale, newMinDB + 10), MAX_DB);
				this.setDBScale(newMinDB, newMaxDB);
			}

			// Automatically re-adjust the sample rate of the source if we zoom too far out or in
			if(source.getSampleRate() < virtualSampleRate && virtualSampleRate < source.getMaxSampleRate())
				source.setSampleRate(source.getNextHigherOptimalSampleRate(virtualSampleRate));
			int nextLower = source.getNextLowerOptimalSampleRate(source.getSampleRate());
			if( (virtualSampleRate < nextLower) && (source.getSampleRate() > nextLower)) {
				source.setSampleRate(nextLower);
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
		// Find out which type of scrolling is requested:
		float hzPerPx = virtualSampleRate / (float) width;
		float dbPerPx = (maxDB-minDB) / (float) getFftHeight();
		float frequencyVariation = (float) Math.min(channelWidth/2.0, virtualSampleRate/10);
		long touchedFrequency = virtualFrequency - virtualSampleRate/2 + (long)(e.getX() * hzPerPx);
		float touchedDB = maxDB - e.getY() * dbPerPx;

		// if the user touched the squelch indicator the user wants to adjust the squelch threshold:
		if(demodulationEnabled 	&& touchedFrequency < channelFrequency + channelWidth
								&& touchedFrequency > channelFrequency - channelWidth
								&& touchedDB < squelch + (maxDB-minDB)/10
								&& touchedDB > squelch - (maxDB-minDB)/10)
			this.scrollType = SCROLLTYPE_SQUELCH;

		// if the user touched the channel frequency the user wants to shift the channel frequency:
		else if(demodulationEnabled	&& e.getY() <= getFftHeight()
								&& touchedFrequency < channelFrequency + frequencyVariation
								&& touchedFrequency > channelFrequency - frequencyVariation)
			this.scrollType = SCROLLTYPE_CHANNEL_FREQUENCY;

		// if the user touched the left channel selector border the user wants to adjust the channel width:
		else if(demodulationEnabled	&& e.getY() <= getFftHeight()
									&& touchedFrequency < channelFrequency-channelWidth + frequencyVariation
									&& touchedFrequency > channelFrequency-channelWidth - frequencyVariation)
			this.scrollType = SCROLLTYPE_CHANNEL_WIDTH_LEFT;

		// if the user touched the right channel selector border the user wants to adjust the channel width:
		else if(demodulationEnabled	&& e.getY() <= getFftHeight()
									&& touchedFrequency < channelFrequency+channelWidth + frequencyVariation
									&& touchedFrequency > channelFrequency+channelWidth - frequencyVariation)
			this.scrollType = SCROLLTYPE_CHANNEL_WIDTH_RIGHT;

		// otherwise the user wants to scroll the virtual frequency
		else
			this.scrollType = SCROLLTYPE_NORMAL;

		return true;
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
			float hzPerPx = virtualSampleRate / (float) width;

			// scroll horizontally or adjust channel selector (scroll type was selected in onDown() event routine:
			switch (this.scrollType) {
				case SCROLLTYPE_NORMAL:
					virtualFrequency = Math.min(Math.max(virtualFrequency + (long) (hzPerPx * distanceX),
							source.getMinFrequency() - source.getSampleRate() / 2), source.getMaxFrequency() + source.getSampleRate() / 2);
					if(virtualFrequency <= 0)	// don't allow negative frequencies
						virtualFrequency = 1;

					// if we scrolled the channel selector out of the window, reset the channel selector:
					if(demodulationEnabled && channelFrequency < virtualFrequency-virtualSampleRate/2) {
						channelFrequency = virtualFrequency-virtualSampleRate/2;
						callbackHandler.onUpdateChannelFrequency(channelFrequency);
					}
					if(demodulationEnabled && channelFrequency > virtualFrequency+virtualSampleRate/2) {
						channelFrequency = virtualFrequency+virtualSampleRate/2;
						callbackHandler.onUpdateChannelFrequency(channelFrequency);
					}
					break;
				case SCROLLTYPE_CHANNEL_FREQUENCY:
					channelFrequency -= distanceX*hzPerPx;
					callbackHandler.onUpdateChannelFrequency(channelFrequency);
					break;
				case SCROLLTYPE_CHANNEL_WIDTH_LEFT:
				case SCROLLTYPE_CHANNEL_WIDTH_RIGHT:
					int tmpChannelWidth = scrollType == SCROLLTYPE_CHANNEL_WIDTH_LEFT
																? (int)(channelWidth+distanceX*hzPerPx)
																: (int)(channelWidth-distanceX*hzPerPx);
					if(callbackHandler.onUpdateChannelWidth(tmpChannelWidth))
						channelWidth = tmpChannelWidth;
					break;
				case SCROLLTYPE_SQUELCH:
					float dbPerPx = (maxDB-minDB) / (float) getFftHeight();
					squelch = squelch + distanceY * dbPerPx;
					callbackHandler.onUpdateSquelch(squelch);
					break;
				default:
					Log.e(LOGTAG,"onScroll: invalid scroll type: " + scrollType);
			}

			// scroll vertically
			if (verticalScrollEnabled && scrollType != SCROLLTYPE_SQUELCH) {
				float yDiff = (maxDB - minDB) * (distanceY / (float) getFftHeight());
				// Make sure we stay in the boundaries:
				if (maxDB - yDiff > MAX_DB)
					yDiff = MAX_DB - maxDB;
				if (minDB - yDiff < MIN_DB)
					yDiff = MIN_DB - minDB;
				this.setDBScale(minDB - yDiff, maxDB - yDiff);
			}

			// Automatically re-tune the source if we scrolled the samples out of the visible window:
			if(source.getFrequency() + source.getSampleRate()/2 < virtualFrequency + virtualSampleRate/2 ||
						source.getFrequency() - source.getSampleRate()/2 > virtualFrequency - virtualSampleRate/2) {
				if(virtualFrequency >= source.getMinFrequency() && virtualFrequency <= source.getMaxFrequency())
					source.setFrequency(virtualFrequency);
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

		// Averaging
		if(averageLength > 0) {
			// verify that the history samples array is correctly initialized:
			if(historySamples == null || historySamples.length != averageLength || historySamples[0].length != mag.length) {
				historySamples = new double[averageLength][mag.length];
				for (int i = 0; i < averageLength; i++) {
					for (int j = 0; j < mag.length; j++) {
						historySamples[i][j] = mag[j];
					}
				}
				oldesthistoryIndex = 0;
			}
			// Check if the frequency or sample rate of the incoming signals is different from the ones before:
			if(frequency != lastFrequency || sampleRate != lastSampleRate) {
				for (int i = 0; i < averageLength; i++) {
					for (int j = 0; j < mag.length; j++) {
						historySamples[i][j] = mag[j];    // reset history. We could also shift and scale. But for now they are simply reset.
					}
				}
			}
			// calculate the averages (store them into mag). copy mag to oldest history index
			double tmp;
			for (int i = 0; i < mag.length; i++) {
				tmp = mag[i];
				for (int j = 0; j < historySamples.length; j++)
					tmp += historySamples[j][i];
				historySamples[oldesthistoryIndex][i] = mag[i];
				mag[i] = tmp / (historySamples.length+1);
			}
			oldesthistoryIndex = (oldesthistoryIndex + 1) % historySamples.length;
		}

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

		// Update Peak Hold
		if(peakHoldEnabled) {
			// First verify that the array is initialized correctly:
			if(peaks == null || peaks.length != mag.length) {
				peaks = new double[mag.length];
				for (int i = 0; i < peaks.length; i++)
					peaks[i] = -999999F;    // == no peak ;)
			}
			// Check if the frequency or sample rate of the incoming signals is different from the ones before:
			if(frequency != lastFrequency || sampleRate != lastSampleRate) {
				for (int i = 0; i < peaks.length; i++)
					peaks[i] = -999999F;    // reset peaks. We could also shift and scale. But for now they are simply reset.
			}
			// Update the peaks:
			for (int i = 0; i < mag.length; i++)
				peaks[i] = Math.max(peaks[i], mag[i]);
		} else {
			peaks = null;
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

		// Update last frequency and sample rate:
		this.lastFrequency = frequency;
		this.lastSampleRate = sampleRate;
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
		float previousY		 = getFftHeight();	// y coordinate of the previously processed pixel (only used with drawing type line)
		float currentY;							// y coordinate of the currently processed pixel
		float samplesPerPx 	= (float) (end-start) / (float) width;		// number of fft samples per one pixel
		float dbDiff 		= maxDB - minDB;
		float dbWidth 		= getFftHeight() / dbDiff; 	// Size (in pixel) per 1dB in the fft
		float scale 		= this.waterfallColorMap.length / dbDiff;	// scale for the color mapping of the waterfall
		float avg;		// Used to calculate the average of multiple values in mag
		float peakAvg;	// Used to calculate the average of multiple values in peaks
		int counter;	// Used to calculate the average of multiple values in mag and peaks

		// Get a canvas from the bitmap of the current waterfall line and clear it:
		Canvas newline = new Canvas(waterfallLines[waterfallLinesTopIndex]);
		newline.drawColor(Color.BLACK);

		// Clear the fft area in the canvas:
		c.drawRect(0, 0, width, getFftHeight(), blackPaint);

		// The start position to draw is either 0 or greater 0, if start is negative:
		int firstPixel = start>=0 ? 0 : (int)((start * -1) / samplesPerPx);

		// We will only draw to the end of mag, not beyond:
		int lastPixel = end>=mag.length ? (int) ((mag.length-start) / samplesPerPx) : (int) ((end-start) / samplesPerPx);

		// Draw pixel by pixel:
		// We start at firstPixel+1 because of integer round off error
		for (int i = firstPixel + 1; i < lastPixel; i++) {
			// Calculate the average value for this pixel:
			avg = 0;
			peakAvg = 0;
			counter = 0;
			for (int j = (int)(i*samplesPerPx); j < (i+1)*samplesPerPx; j++) {
				avg += mag[j + start];
				if(peaks != null)
					peakAvg += peaks[j + start];
				counter++;
			}
			avg = avg / counter;
			if(peaks != null)
				peakAvg = peakAvg / counter;

			// FFT:
			if(avg > minDB) {
				currentY = getFftHeight() - (avg - minDB) * dbWidth;
				if(currentY < 0 )
					currentY = 0;
				switch (fftDrawingType) {
					case FFT_DRAWING_TYPE_BAR:
						c.drawLine(i, getFftHeight(), i, currentY, fftPaint);
						break;
					case FFT_DRAWING_TYPE_LINE:
						c.drawLine(i-1,previousY,i,currentY, fftPaint);
						previousY = currentY;

						// We have to draw the last line to the bottom if we're in the last round:
						if(i+1 == lastPixel)
							c.drawLine(i,previousY,i+1,getFftHeight(), fftPaint);
						break;
					default:
						Log.e(LOGTAG,"drawFFT: Invalid fft drawing type: " + fftDrawingType);
				}
			}

			// Peak:
			if(peaks != null) {
				if(peakAvg > minDB) {
					peakAvg = getFftHeight() - (peakAvg - minDB) * dbWidth;
					if(peakAvg > 0 )
						c.drawPoint(i,peakAvg,peakHoldPaint);
				}
			}

			// Waterfall:
			if(avg <= minDB)
				waterfallLinePaint.setColor(waterfallColorMap[0]);
			else if(avg >= maxDB)
				waterfallLinePaint.setColor(waterfallColorMap[waterfallColorMap.length-1]);
			else
				waterfallLinePaint.setColor(waterfallColorMap[(int)((avg-minDB)*scale)]);
			newline.drawLine(i, 0, i, getPixelPerWaterfallLine(), waterfallLinePaint);
		}

		// If demodulation is activated: draw channel selector:
		if(demodulationEnabled) {
			float pxPerHz = width / (float) virtualSampleRate;
			float channelPosition = width/2 + pxPerHz * (channelFrequency - virtualFrequency);
			float leftBorder = channelPosition - pxPerHz * channelWidth;
			float rightBorder = channelPosition + pxPerHz * channelWidth;
			float squelchPosition =  getFftHeight() - (squelch - minDB) * dbWidth;

			// draw half transparent channel area:
			demodSelectorPaint.setAlpha(0x7f);
			c.drawRect(leftBorder, 0, rightBorder, squelchPosition, demodSelectorPaint);

			// draw center and borders:
			demodSelectorPaint.setAlpha(0xff);
			c.drawLine(channelPosition,getFftHeight(), channelPosition, 0, demodSelectorPaint);
			c.drawLine(leftBorder,getFftHeight(), leftBorder, 0, demodSelectorPaint);
			c.drawLine(rightBorder,getFftHeight(), rightBorder, 0, demodSelectorPaint);
			c.drawLine(leftBorder,squelchPosition,rightBorder,squelchPosition,demodSelectorPaint);
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

	/**
	 * Interface used to report user actions (channel frequency/width changes)
	 */
	public interface CallbackInterface {
		/**
		 * Is called when the user adjusts the channel width.
		 *
		 * @param newChannelWidth	new channel width (single sided) in Hz
		 * @return true if valid width; false if width is out of range
		 */
		public boolean onUpdateChannelWidth(int newChannelWidth);

		/**
		 * Is called when the user adjusts the channel frequency.
		 *
		 * @param newChannelFrequency	new channel frequency in Hz
		 */
		public void onUpdateChannelFrequency(long newChannelFrequency);

		/**
		 * Is called when the user adjusts the squelch threshold.
		 *
		 * @param newSquelch	new squelch threshold in dB
		 */
		public void onUpdateSquelch(float newSquelch);
	}
}

