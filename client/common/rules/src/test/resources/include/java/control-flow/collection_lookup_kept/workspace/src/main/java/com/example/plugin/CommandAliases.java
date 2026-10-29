package com.example.plugin;

import java.util.Map;

final class CommandAliases
{
	private static final Map<String, String> ALIASES = Map.of("ls", "list", "rm", "remove", "mv", "move");

	static boolean isAlias(String name)
	{
		if (name == null)
			return false;
		return ALIASES.containsKey(name);
	}
}
