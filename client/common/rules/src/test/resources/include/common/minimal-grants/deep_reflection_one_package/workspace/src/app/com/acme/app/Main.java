package com.acme.app;

import com.acme.sizing.Capacity;

import java.util.ArrayList;

/**
 * Prints the size and capacity of a list as elements are added.
 */
public final class Main
{
	private Main()
	{
	}

	/**
	 * @param args unused
	 */
	public static void main(String[] args)
	{
		ArrayList<Integer> list = new ArrayList<>();
		for (int i = 1; i <= 12; ++i)
		{
			list.add(i);
			if (i % 4 == 0)
				System.out.println("size " + list.size() + ", capacity " + Capacity.of(list));
		}
	}
}
