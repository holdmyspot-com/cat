package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.util.List;

/**
 * The entry point that both harnesses' {@code prompt-optimizer} launchers share.
 */
public final class PromptOptimizerCli
{
	/**
	 * Prevents instantiation.
	 */
	private PromptOptimizerCli()
	{
	}

	/**
	 * Runs one command.
	 *
	 * @param host what the harness's {@code main} resolved
	 * @param args the launcher's arguments
	 * @param out where the JSON goes
	 * @return the exit code: 0 for a result, 1 for a refusal or a failure, 2 for invalid input
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(Host host, List<String> args, PrintStream out)
	{
		requireThat(host, "host").isNotNull();
		requireThat(args, "args").isNotNull();
		requireThat(out, "out").isNotNull();
		return new Commands(new OptimizerCore(host), host.json()).execute(args, out);
	}

	/**
	 * Prints a failure that happened before a command could run.
	 *
	 * @param json the JSON mapper
	 * @param out where the JSON goes
	 * @param message what failed
	 * @return the exit code, which is 1
	 * @throws NullPointerException if any argument is null
	 */
	public static int fail(JsonMapper json, PrintStream out, String message)
	{
		requireThat(json, "json").isNotNull();
		requireThat(out, "out").isNotNull();
		requireThat(message, "message").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("status", "error");
		root.put("message", message);
		out.println(json.writeValueAsString(root));
		return 1;
	}
}
