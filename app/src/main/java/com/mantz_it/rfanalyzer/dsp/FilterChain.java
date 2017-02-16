package com.mantz_it.rfanalyzer.dsp;

import android.support.annotation.NonNull;

import com.mantz_it.rfanalyzer.dsp.spi.Filter;
import com.mantz_it.rfanalyzer.dsp.spi.Packet;

import java.nio.FloatBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by Pavel on 13.11.2016.
 */

public class FilterChain implements Filter {
	protected final List<Filter> filters;
	protected Packet tmp1, tmp2;

	public static FilterChainBuilder getDefaultBuilder() {
		return new FilterChainBuilder();
	}

	FilterChain(@NonNull List<Filter> filters,@NonNull Packet tmp1,@NonNull Packet tmp2) {
		this.filters = filters;
		this.tmp1 = tmp1;
		this.tmp2 = tmp2;
	}

	@Override
	public synchronized int apply(@NonNull Packet in,@NonNull Packet out) {
		checkBuffers(in.getBuffer(), out.getBuffer());
		Iterator<Filter> iter = filters.listIterator();
		tmp1.getBuffer().clear();
		int cnt = 0;
		while (iter.hasNext()) {
			tmp2.getBuffer().clear();
			cnt = iter.next().apply(tmp1, tmp2);
			tmp2.getBuffer().flip();
			// swap for the next turn
			final Packet tmp = tmp2;
			tmp2 = tmp1;
			tmp1 = tmp;
		}
		out.getBuffer().put(tmp1.getBuffer());
		return cnt;
	}

	protected void checkBuffers(FloatBuffer in, FloatBuffer out) {
		if (in == null || out == null)
			throw new NullPointerException();
		if (!tmp1.getClass().isAssignableFrom(in.getClass()))
			throw new IllegalArgumentException("Temporary buffer is incompatible with input");
		//if (in.remaining() > tmp1.capacity())
		//	throw new IndexOutOfBoundsException("Remaining size is over temporary buffer capacity");
		if (out.isReadOnly())
			throw new ReadOnlyBufferException();
	}

	public static class FilterChainBuilder {
		protected final List<Filter> list;
		protected Packet tmp1, tmp2;

		protected FilterChainBuilder() {
			list = new LinkedList<>();
		}

		public FilterChainBuilder addFilter(@NonNull Filter f) {
			synchronized (list) {
				list.add(f);
			}
			return this;
		}

		public FilterChainBuilder setBuffers(@NonNull Packet tmp1,@NonNull Packet tmp2) {
			if (!tmp2.getClass().isAssignableFrom(tmp1.getClass()) || !tmp1.getClass().isAssignableFrom(tmp2.getClass()))
				throw new IllegalArgumentException("tmp1 and tmp2 have incompatible types");
			if (tmp1.getBuffer().isReadOnly() || tmp2.getBuffer().isReadOnly())
				throw new ReadOnlyBufferException();
			this.tmp1 = tmp1;
			this.tmp2 = tmp2;
			return this;
		}

		@NonNull
		public FilterChain build() {
			if (list.size() < 1)
				throw new IllegalArgumentException();
			if (list.size() < 2)
				throw new IllegalArgumentException("For only one apply use pure Filter instead.");
			if (tmp1 == null || tmp2 == null)
				throw new NullPointerException("Temporary buffers must be non-null");

			return new FilterChain(list, tmp1, tmp2);
		}

	}
}

