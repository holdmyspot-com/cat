package com.holdmyspot.cat.claude.policy;

/**
 * The scopes of Claude Code settings, from the highest precedence to the lowest.
 */
enum SettingsScope
{
	/**
	 * The settings that an administrator manages.
	 */
	MANAGED,
	/**
	 * The settings that {@code --settings} gives.
	 */
	FLAG,
	/**
	 * The project's settings that are not shared, in {@code .claude/settings.local.json}.
	 */
	LOCAL,
	/**
	 * The project's shared settings, in {@code .claude/settings.json}.
	 */
	PROJECT,
	/**
	 * The user's settings, in Claude Code's configuration folder.
	 */
	USER
}
