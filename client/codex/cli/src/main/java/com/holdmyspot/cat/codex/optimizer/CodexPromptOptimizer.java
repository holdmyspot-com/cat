package com.holdmyspot.cat.codex.optimizer;

import com.holdmyspot.cat.common.optimizer.cli.PromptOptimizerCli;

/**
 * The {@code prompt-optimizer} launcher for Codex. It runs one command in a {@link CodexOptimizerCommandScope}, which
 * resolves the process environment and wires the Codex adapters into the harness-neutral core.
 */
public final class CodexPromptOptimizer
{
	/**
	 * Prevents instantiation.
	 */
	private CodexPromptOptimizer()
	{
	}

	/**
	 * Runs one command and exits with its code if it is not zero.
	 *
	 * @param args the command and its arguments
	 */
	public static void main(String[] args)
	{
		PromptOptimizerCli.main(args, CodexOptimizerCommandScope::new);
	}
}
