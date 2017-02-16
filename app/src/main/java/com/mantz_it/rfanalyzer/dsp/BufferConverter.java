package com.mantz_it.rfanalyzer.dsp;

import android.support.annotation.NonNull;

import com.mantz_it.rfanalyzer.dsp.spi.Conversion;

import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Created by Pavel on 15.11.2016.
 */


/**
 * Just a namespace wrapper for small converter classes.
 * These converters are simple singletons, we don't need multiple instances of them,
 * but we can't make method of interface static, so just retrieve single existent instance by <code>getInstance()</code>.
 */
// todo: improve architecture
public class BufferConverter<I extends Buffer, O extends Buffer> {

public static final class S8ToFloat implements Conversion<ByteBuffer, FloatBuffer> {
	protected static S8ToFloat instance;

	@NonNull
	public static S8ToFloat getInstance() {
		if (instance == null) instance = new BufferConverter.S8ToFloat();
		return instance;
	}

	protected S8ToFloat() {}

	@Override
	public void convert(@NonNull ByteBuffer in, @NonNull FloatBuffer out) {
		try {
			while (true) out.put((in.get() / 128.f));
		} catch (BufferUnderflowException | BufferOverflowException itsOk) {
			// really, packets are big, why check everything few times, it should be ok
		}
	}
}

public static final class U8ToFloat implements Conversion<ByteBuffer, FloatBuffer> {
	protected static U8ToFloat instance;

	@NonNull
	public static U8ToFloat getInstance() {
		if (instance == null) instance = new BufferConverter.U8ToFloat();
		return instance;
	}

	protected U8ToFloat() {}

	@Override
	public void convert(@NonNull ByteBuffer in, @NonNull FloatBuffer out) {
		try {
			while (true) out.put(((in.get() & 0xff) - 127.4f) / 128.f);
		} catch (BufferUnderflowException | BufferOverflowException itIsOk) {
			// really, packets are big, why check everything few times, it should be ok
		}
	}
}

public static final class S24LEToFloat implements Conversion<ByteBuffer, FloatBuffer> {
	protected static S24LEToFloat instance;

	@NonNull
	public static S24LEToFloat getInstance() {
		if (instance == null) instance = new S24LEToFloat();
		return instance;
	}

	protected S24LEToFloat() {}

	protected static final float CONVERTER_SCALE = (1 << 23);

	@Override
	public void convert(@NonNull ByteBuffer in, @NonNull FloatBuffer out) {
		try {
			while (true) {
				final float tmp =
						((in.get() & 0xff) | (in.get() & 0xff) << 8 | in.get() << 16) / CONVERTER_SCALE;
				out.put(tmp);
			}
		} catch (BufferUnderflowException | BufferOverflowException itsOk) {
			// really, packets are big, why check everything few times, it should be ok
		}
	}
}

public static final class FloatToS16 implements Conversion<FloatBuffer, ShortBuffer> {

	@Override
	public void convert(@NonNull FloatBuffer in, @NonNull ShortBuffer out) {
		try {
			while (true) out.put((short) (in.get() * Short.MAX_VALUE));
		} catch (BufferUnderflowException | BufferOverflowException ignore) {
			// really, packets are big, why check everything few times, it should be ok
		}
	}
}

}
