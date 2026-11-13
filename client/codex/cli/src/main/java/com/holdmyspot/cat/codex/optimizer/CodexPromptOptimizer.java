package com.holdmyspot.cat.codex.optimizer;

import tools.jackson.databind.json.JsonMapper;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.Pause;
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
		if (args.length > 0 && args[0].equals("contract") && CodexOptimizerBoundary.outside(
			PluginDirectory.of(CodexPromptOptimizer.class)))
		{
			System.exit(PromptOptimizerCli.fail(JsonMapper.builder().build(), System.out,
				"Contract tools must run inside the contract author's sandbox."));
			return;
		}
		PromptOptimizerCli.main(args, () -> new CodexOptimizerCommandScope(Pause::sleeping));
	}
}
