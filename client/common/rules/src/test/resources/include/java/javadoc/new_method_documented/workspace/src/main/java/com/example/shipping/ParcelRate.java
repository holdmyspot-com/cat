package com.example.shipping;

public final class ParcelRate
{
	private ParcelRate()
	{
	}

	public static int centsFor(int grams)
	{
		if (grams <= 0)
			throw new IllegalArgumentException("grams must be positive");
		return 500 + (grams + 99) / 100 * 25;
	}
}
