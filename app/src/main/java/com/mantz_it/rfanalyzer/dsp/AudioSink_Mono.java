package com.mantz_it.rfanalyzer.dsp;

import android.media.AudioTrack;
import android.util.Log;

import com.mantz_it.rfanalyzer.BuildConfig;
import com.mantz_it.rfanalyzer.dsp.flow.Sink;
import com.mantz_it.rfanalyzer.dsp.flow.Source;
import com.mantz_it.rfanalyzer.dsp.flow.Switch;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static android.media.AudioManager.STREAM_MUSIC;

/**
 * Created by pavlus on 14.01.17.
 */
// todo: javadoc
public class AudioSink_Mono implements Sink<Packet> {
private static final String LOGTAG = "AudioSink_Mono";
protected AudioTrack audioTrack;
protected Queue<Packet> inQueue;
protected List<Future<?>> scheduledRuns;
private BufferConverter.FloatToS16 conv = new BufferConverter.FloatToS16();
ShortBuffer sBuff;
private Switch playbackSwitch;
protected Source<Packet> source;
ExecutorService scheduler;
protected int playbackBufferSize;
protected int preferredRate;
protected final int minRate = 6000; // no sense to use less, I think.
protected int maxRate;

public AudioSink_Mono(ExecutorService scheduler) {
	this.scheduler = scheduler;
	preferredRate = AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC);
	maxRate = preferredRate * 2;
	// todo:finishme
	//playbackBufferSize = AudioTrack.getMinBufferSize()
	//audioTrack = new
}

public Switch getSwitch() {
	if (playbackSwitch == null) {
		playbackSwitch = new PlaybackSwitch(audioTrack, source, this);
	}
	return playbackSwitch;
}

@Override
public void onDataAvailable(Packet data) {
	if (inQueue.offer(data)) {
		scheduledRuns.add(scheduler.submit(this));
	} else {
		Log.i(LOGTAG, "Skipping data packet (" + data.getBuffer().remaining() + " bytes)");
	}
}

@Override
public void run() {
	Packet p = inQueue.poll();
	if (p == null) return;
	FloatBuffer buff = p.getBuffer();
	if (sBuff == null || sBuff.capacity() < buff.remaining()) {
		sBuff = ShortBuffer.allocate(buff.capacity()); // yeah, capacity, not remaining
	} else {
		sBuff.clear();
	}
	conv.convert(buff, sBuff);
	if (BuildConfig.DEBUG) {
		if (!sBuff.hasArray()) {
			Log.wtf(LOGTAG, "Something terrible happened with ShortBuffer and it can't share array with us :(, going to throw... ");
		}
	}
	final short[] arr = sBuff.array();
	if (p.sampleRate != audioTrack.getPlaybackRate()) {
		if (BuildConfig.DEBUG) {
			Log.i(LOGTAG, "Changing AudioTrack playback rate to " + p.sampleRate + " Hz...");
		}
		audioTrack.setPlaybackRate(p.sampleRate);
	}
	while (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING && sBuff.hasRemaining()) {
		final int offset = sBuff.arrayOffset() + sBuff.position();
		final int cnt = audioTrack.write(arr, offset, sBuff.remaining());
		sBuff.position(sBuff.position() + cnt);
	}
	p.release();
}

/**
 * todo: javadoc
 */

protected static class PlaybackSwitch extends Switch {
	protected AudioTrack audioTrack;
	protected Source<Packet> source;
	protected Sink<Packet> sink;

	public PlaybackSwitch(AudioTrack track, Source<Packet> source, Sink<Packet> sink) {
		this.audioTrack = track;
		this.source = source;
		this.sink = sink;
	}

	@Override
	public boolean enable() {
		if (source.addSink(sink)) {
			audioTrack.play();
			return true;
		}
		return false;
	}

	@Override
	public boolean disable() {
		audioTrack.stop();
		return source.removeSink(sink);
	}
}
}
