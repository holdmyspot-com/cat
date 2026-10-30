package com.example.labels;

import java.util.List;

public final class Labels
{
	private Labels()
	{
	}

	public static String first(List<String> names)
	{
		if (names.isEmpty())
			return "";
		return names.getFirst();
	}
}
