package com.example.plugin;

import java.util.Set;
import java.util.TreeSet;

final class ExportFormats
{
	private static final Set<String> FORMATS = Set.of("csv", "json", "{{setting}}");

	static String usage()
	{
		return "Supported formats: " + String.join(", ", new TreeSet<>(FORMATS));
	}

	static boolean isSupported(String format)
	{
		return format.equals("csv") || format.equals("json") || format.equals("{{setting}}");
	}
}
