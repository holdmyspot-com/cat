package com.holdmyspot.cat.claude.optimizer;

import com.holdmyspot.cat.common.optimizer.cli.PromptOptimizerCli;

/**
 * The {@code prompt-optimizer} launcher for Claude Code. It runs one command in a
 * {@link ClaudeOptimizerCommandScope}, which resolves the process environment and wires the Claude adapters into the
 * harness-neutral core.
 */
public final class ClaudePromptOptimizer
{
	/**
	 * Prevents instantiation.
	 */
	private ClaudePromptOptimizer()
	{
	}

	/**
	 * Runs one command and exits with its code if it is not zero.
	 *
	 * @param args the command and its arguments
	 */
	public static void main(String[] args)
	{
		PromptOptimizerCli.main(args, ClaudeOptimizerCommandScope::new);
	}
}
