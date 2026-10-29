package com.acme.sizing;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Reports how many elements a list can hold before it grows.
 */
public final class Capacity
{
	private static final Field ELEMENT_DATA;

	static
	{
		try
		{
			ELEMENT_DATA = ArrayList.class.getDeclaredField("elementData");
			ELEMENT_DATA.setAccessible(true);
		}
		catch (NoSuchFieldException e)
		{
			throw new ExceptionInInitializerError(e);
		}
	}

	private Capacity()
	{
	}

	/**
	 * @param list a list
	 * @return the length of the list's backing array
	 */
	public static int of(ArrayList<?> list)
	{
		try
		{
			return ((Object[]) ELEMENT_DATA.get(list)).length;
		}
		catch (IllegalAccessException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
