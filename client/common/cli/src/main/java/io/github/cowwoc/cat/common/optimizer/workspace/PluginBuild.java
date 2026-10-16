package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.optimizer.contract.TrialPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the build command of a trial plugin that the project builds.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class PluginBuild
{
	/**
	 * The most bytes of the build's output that CAT keeps, from its end, so that a long build cannot exhaust memory.
	 */
	private static final int TAIL_BYTES = 8192;
	/**
	 * The most lines of the build's output that a failure shows, from its end.
	 */
	private static final int TAIL_LINES = 40;

	/**
	 * Prevents construction.
	 */
	private PluginBuild()
	{
	}

	/**
	 * Runs a build command in a worktree and waits for it to end. A program that names a directory is resolved
	 * against the worktree; any other program is looked up on the {@code PATH}.
	 *
	 * @param worktree the directory that the command runs in
	 * @param build the build
	 * @return how the command ended
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the command cannot start or its output cannot be read
	 * @throws InterruptedException if the thread is interrupted while it waits; the command is then destroyed
	 */
	public static Result run(Path worktree, TrialPlugin.Build build) throws IOException, InterruptedException
	{
		requireThat(worktree, "worktree").isNotNull();
		requireThat(build, "build").isNotNull();
		ProcessBuilder builder = new ProcessBuilder().directory(worktree.toFile()).redirectErrorStream(true);
		List<String> command = new ArrayList<>(build.command());
		String program = command.getFirst();
		if (program.contains("/") || program.contains("\\"))
			program = worktree.resolve(program).toString();
		command.set(0, Platform.current().findProgram(program, builder.environment()));
		// Closing the process destroys it, so a failure or an interrupt does not leave the build running
		try (Process process = builder.command(command).start())
		{
			process.getOutputStream().close();
			String tail = tail(process.getInputStream());
			return new Result(process.waitFor(), tail);
		}
	}

	/**
	 * Reads a stream to its end, keeping the last lines.
	 *
	 * @param in the stream
	 * @return at most {@value #TAIL_LINES} lines from the last {@value #TAIL_BYTES} bytes
	 * @throws IOException if the stream cannot be read
	 */
	private static String tail(InputStream in) throws IOException
	{
		byte[] ring = new byte[TAIL_BYTES];
		long total = 0;
		byte[] buffer = new byte[TAIL_BYTES];
		while (true)
		{
			int count = in.read(buffer);
			if (count < 0)
				break;
			for (int i = 0; i < count; ++i)
				ring[(int) ((total + i) % TAIL_BYTES)] = buffer[i];
			total += count;
		}
		int kept = (int) Math.min(total, TAIL_BYTES);
		byte[] last = new byte[kept];
		for (int i = 0; i < kept; ++i)
			last[i] = ring[(int) ((total - kept + i) % TAIL_BYTES)];
		List<String> lines = new String(last, StandardCharsets.UTF_8).lines().toList();
		return String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
	}

	/**
	 * How a build command ended.
	 *
	 * @param exitStatus the command's exit status
	 * @param tail the end of the command's combined output
	 */
	public record Result(int exitStatus, String tail)
	{
		/**
		 * Creates a result.
		 *
		 * @param exitStatus the command's exit status
		 * @param tail the end of the command's combined output
		 * @throws NullPointerException if {@code tail} is null
		 */
		public Result
		{
			requireThat(tail, "tail").isNotNull();
		}
	}
}
