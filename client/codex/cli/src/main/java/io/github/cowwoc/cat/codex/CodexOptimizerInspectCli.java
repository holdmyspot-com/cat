package io.github.cowwoc.cat.codex;

import io.github.cowwoc.cat.codex.runner.CodexOptimizerAdapter;
import io.github.cowwoc.cat.common.OptimizerInspectCommand;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.optimizer.runner.NestedAgent;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessTemporaryRoot;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

/** Starts the installed optimizer inspection command with Codex's native adapter. */
public final class CodexOptimizerInspectCli
{
	/**
	 * Starts the installed inspection command with the runner inside this plugin artifact.
	 *
	 * @param args target and current host tool-call identity
	 * @throws IOException if campaign preparation, native execution, or output fails
	 */
	public static void main(String[] args) throws IOException
	{
		Path workingDirectory = Path.of(System.getProperty("user.dir"));
		Path artifact = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().getParent();
		try (ToolScope scope = ToolScope.forWorkingDirectory(workingDirectory))
		{
			HarnessRunManager manager = new HarnessRunManager(
				HarnessTemporaryRoot.defaultRunRoot(scope.catTemp()), Clock.systemUTC());
			Path workflowRoot = scope.catWork().resolve("optimizer");
			OptimizerInspectCommand.run(args, workingDirectory, artifact, manager, (store, binding) -> new NestedAgent(
				new CodexOptimizerAdapter(store, binding, manager, artifact,
					workflowRoot.resolve("bridge-transcripts"), scope.catTemp(), "codex", System.getenv()),
				store::readImmutableEvidence), System.out);
		}
	}

	/** Prevents construction of utility class instances. */
	private CodexOptimizerInspectCli()
	{
	}
}
