package com.example.plugin;

import java.util.Map;
import java.util.Set;

final class PluginSettings
{
	private static final Set<String> PERSISTED_SETTINGS = Set.of("queue", "user", "product", "{{setting}}");

	static void validate(Map<String, String> settings)
	{
		for (Map.Entry<String, String> entry : settings.entrySet())
		{
			if (!PERSISTED_SETTINGS.contains(entry.getKey()))
				throw new IllegalArgumentException("Unknown plugin setting: " + entry.getKey());
		}
	}
}
