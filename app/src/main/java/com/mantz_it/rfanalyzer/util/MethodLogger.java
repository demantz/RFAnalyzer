package com.mantz_it.rfanalyzer.util;

import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Created by Pavel on 28.03.2017.
 */

public class MethodLogger implements InvocationHandler {
private final String LOGTAG;
private static final String PACKAGE_ROOT = "com.mantz_it.rfanalyzer";

public Object getDirectInstance() {
	return instance;
}

private final Object instance;

public MethodLogger(Object instance, String logtag) {
	Log.i("MethodLogger", String.format("new MethodLogger instance for object %s[%d]", instance.toString(), instance.hashCode()));
	Log.i("MethodLogger", getStackTraceString());
	this.instance = instance;
	LOGTAG = logtag;
}

public MethodLogger(Object instance) {
	this.instance = instance;
	LOGTAG = "MethodProxy";
}

@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
	try {
		Object result = method.invoke(instance, args);
		Log.d(LOGTAG, String.format("%s[%d]%s.%s(%s)\n\t=> %s",
				getStackTraceString(),
				instance.hashCode(),
				instance.getClass().getSimpleName(),
				method.getName(),
				args.length == 0 ? "" : Arrays.toString(args),
				result == null ? "null" : result.toString()
		));

		return result;
	}
	catch (InvocationTargetException ite) {
		throw ite.getCause();
	}
}

public String getStackTraceString() {
	StackTraceElement[] trace = Thread.currentThread().getStackTrace();
	StringBuilder sb = new StringBuilder();
	boolean skip = true; // skip outer calls, start from topmost known
	for (int i = trace.length - 1; i > 3; --i) {
		final StackTraceElement el = trace[i];
		if (skip) {
			if (el.getClassName().startsWith(PACKAGE_ROOT))
				skip = false;
			else
				continue;
		}
		sb.append("in "
		          + el.getClassName()
		          + "."
		          + el.getMethodName()
		          + " ("
		          + el.getFileName()
		          + ":"
		          + el.getLineNumber()
		          + ")\n");
	}
	return sb.toString();
}
}
