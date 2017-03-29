package com.mantz_it.rfanalyzer;

import com.mantz_it.rfanalyzer.sdr.controls.MixerFrequency;
import com.mantz_it.rfanalyzer.util.MethodLogger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;

/**
 * Created by Pavel on 28.03.2017.
 */
public class Signed24BitIQConverterTest {
@Test
public void frequencyControl() {
	final long[] vals = {1000L, 0L, Long.MAX_VALUE, 233123123L, Long.MIN_VALUE};
	IQConverter converter = new Signed24BitIQConverter();
	MixerFrequency mixerFrequency = converter.getControl(MixerFrequency.class);
	if(Proxy.isProxyClass(mixerFrequency.getClass()))
		mixerFrequency = (MixerFrequency) ((MethodLogger)Proxy.getInvocationHandler(mixerFrequency)).getDirectInstance();
	for (long val :
			vals) {
		mixerFrequency.set(val);
		Assert.assertEquals(val, mixerFrequency.get().longValue());
	}
}
}