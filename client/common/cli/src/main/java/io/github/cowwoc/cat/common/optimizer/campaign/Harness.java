package io.github.cowwoc.cat.common.optimizer.campaign;

import java.util.Locale;

/**
 * The harness that started a campaign and is the only one allowed to advance it.
 */
public enum Harness
{
	/**
	 * Claude Code.
	 */
	CLAUDE,
	/**
	 * Codex.
	 */
	CODEX;

	/**
	 * Returns the harness name used in commands and display text.
	 *
	 * @return the enum name in lower case using the root locale
	 */
	public String commandName()
	{
		return name().toLowerCase(Locale.ROOT);
	}
}
