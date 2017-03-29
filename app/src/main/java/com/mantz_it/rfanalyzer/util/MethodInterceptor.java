package com.mantz_it.rfanalyzer.util;

import java.lang.reflect.Proxy;

/**
 * Created by Pavel on 28.03.2017.
 */

public class MethodInterceptor {
@SuppressWarnings("unchecked")
public static <T> T wrapWithLog(T instance, String logtag, Class<?>... clazzez) {
	if(Proxy.isProxyClass(instance.getClass()))
		if(Proxy.getInvocationHandler(instance).getClass().isAssignableFrom(MethodLogger.class))
			return instance;
	return (T) Proxy.newProxyInstance(instance.getClass().getClassLoader(),
			clazzez,
			new MethodLogger(instance, logtag));
}

@SuppressWarnings("unchecked")
public static <T> T wrapWithLog(T instance, Class<?>... clazzez) {
	return (T) Proxy.newProxyInstance(instance.getClass().getClassLoader(),
			clazzez,
			new MethodLogger(instance));
}
}
