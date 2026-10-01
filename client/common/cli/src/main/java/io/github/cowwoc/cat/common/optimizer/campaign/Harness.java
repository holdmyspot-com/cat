package io.github.cowwoc.cat.common.optimizer.campaign;

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
	CODEX
}
