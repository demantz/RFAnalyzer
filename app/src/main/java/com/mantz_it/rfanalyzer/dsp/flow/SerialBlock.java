package com.mantz_it.rfanalyzer.dsp.flow;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Pavel on 10.01.2017.
 */

public abstract class SerialBlock<I, O> extends Block<I, O> {
	protected int retriesCntBeforeInsist = 2;
	protected Lock lock = new ReentrantLock(); // todo: add Lock to parameters list, probably?
	protected AtomicInteger rescheduleCnt;

	public SerialBlock(@NonNull BlockingQueue<I> queue,
	                   @NonNull Set<Sink<O>> sinks,
	                   @NonNull ExecutorService scheduler,
	                   @IntRange(from = 0) int accumulateCnt,
	                   @IntRange(from = 0) int retriesBeforeInsist) {
		super(queue, sinks, scheduler, accumulateCnt);
		this.retriesCntBeforeInsist = retriesBeforeInsist;
	}

	@Override
	public final void run() {
		if (lock.tryLock()) {
			// free to go
			try { serialRun(); }
			finally { lock.unlock(); }
		} else {
			// running already
			if (rescheduleCnt.incrementAndGet() < retriesCntBeforeInsist) {
				scheduler.submit(this); // reschedule
			} else {
				// insist on execution next, so we won't be spinning if no other tasks arrived for few tries
				lock.lock();
				try {serialRun();}
				finally { lock.unlock(); }
			}
		}
	}

	public abstract void serialRun();
}
