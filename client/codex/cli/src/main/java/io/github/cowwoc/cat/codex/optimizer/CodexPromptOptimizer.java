package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.cli.PromptOptimizerCli;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

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
		requireThat(args, "args").isNotNull();
		int exitCode;
		try (CodexOptimizerCommandScope scope = new CodexOptimizerCommandScope())
		{
			exitCode = PromptOptimizerCli.run(scope, List.of(args), System.out);
		}
		catch (IOException e)
		{
			// The scope could not be created, so there is no scope-owned mapper to render the failure
			exitCode = PromptOptimizerCli.fail(JsonMapper.builder().build(), System.out, e.getMessage());
		}
		if (exitCode != 0)
			System.exit(exitCode);
	}
}
