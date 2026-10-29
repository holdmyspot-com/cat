package com.acme.digest;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Measures strings with the C library's {@code strlen}.
 */
public final class NativeLength
{
	private static final MethodHandle STRLEN;

	static
	{
		Linker linker = Linker.nativeLinker();
		STRLEN = linker.downcallHandle(linker.defaultLookup().findOrThrow("strlen"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
	}

	private NativeLength()
	{
	}

	/**
	 * @param text a string
	 * @return the number of bytes in the string's UTF-8 encoding
	 */
	public static long of(String text)
	{
		try (Arena arena = Arena.ofConfined())
		{
			return (long) STRLEN.invokeExact(arena.allocateFrom(text));
		}
		catch (Throwable e)
		{
			throw new IllegalStateException(e);
		}
	}
}
