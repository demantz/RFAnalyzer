package com.mantz_it.rfanalyzer.dsp.flow;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;

/**
 * Created by Pavel on 10.01.2017.
 */

public abstract class Block<I, O> implements Sink<I>, Source<O> {
	protected int accumulateCount = 1;
	protected BlockingQueue<I> queue;
	protected Set<Sink<O>> sinks;
	protected ExecutorService scheduler;

	public Block(
			@NonNull BlockingQueue<I> queue,
			@NonNull Set<Sink<O>> sinks,
			@NonNull ExecutorService scheduler,
			@IntRange(from = 0) int accumulateCnt) {
		this.queue = queue;
		this.sinks = sinks;
		this.scheduler = scheduler;
		this.accumulateCount = accumulateCnt;
	}

	public Block(
			@NonNull BlockingQueue<I> queue,
			@NonNull Set<Sink<O>> sinks,
			@NonNull ExecutorService scheduler) {
		this(queue, sinks, scheduler, 0);
	}

	@Override
	public void onDataAvailable(I data) {
		queue.offer(data); // FIXME: unreliable block, add support of reliable
		if (queue.size() >= accumulateCount)
			scheduler.submit(this);
	}

	@Override
	public boolean addSink(Sink<O> sink) {
		return sinks.add(sink);
	}

	@Override
	public boolean removeSink(Sink<O> sink) {
		return sinks.remove(sink);
	}

}
