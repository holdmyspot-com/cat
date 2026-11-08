package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/**
 * Learns a child's needs from the runner of the child's harness, which alone knows the harness's model host, its
 * dependency caches and the login that its isolated home copies. The runner runs with
 * {@link ChildNeedsReport#OPTION} in the request's directory and writes {@link ChildNeedsReport#FILE_NAME} there.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RunnerChildNeeds implements ChildNeedsSource
{
	/**
	 * How long the runner may take to report.
	 */
	private static final Duration REPORT_WAIT = Duration.ofSeconds(60);
	/**
	 * The most bytes of the runner's error output that a failure quotes.
	 */
	private static final int STDERR_TAIL_BYTES = 4096;
	private final RequestedSessionStarter starter;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a source.
	 *
	 * @param starter starts the runner of a child's harness
	 * @throws NullPointerException if {@code starter} is null
	 */
	public RunnerChildNeeds(RequestedSessionStarter starter)
	{
		requireThat(starter, "starter").isNotNull();
		this.starter = starter;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The child needs its run folder, the request's denied reads, and the writes and hosts that its harness's runner
	 * reports. A child whose isolated home would copy a login file that {@code bound} denies reading is refused.
	 *
	 * @throws IOException if the runner cannot start, does not report within 60 seconds, fails, or writes an invalid
	 *                     report
	 */
	@Override
	public ChildNeeds needs(String harness, Path runFolder, List<Path> deniedReads, ParentPolicy bound)
		throws IOException, PolicyRefusal
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(runFolder, "runFolder").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(bound, "bound").isNotNull();
		Path directory = runFolder.toAbsolutePath().getParent();
		Path file = directory.resolve(ChildNeedsReport.FILE_NAME);
		try (Process process = starter.start(harness, List.of(ChildNeedsReport.OPTION, file.toString()), directory))
		{
			awaitSuccess(harness, process, directory);
		}
		ChildNeedsReport report = ChildNeedsReport.read(json, file);
		Files.deleteIfExists(file);
		return needs(harness, report, runFolder, deniedReads, bound);
	}

	/**
	 * Returns what a child needs, as its harness's runner reports it, once the parent's policy lets the parent's
	 * commands read every login that the child copies: the child's commands could read the copy in its run folder.
	 *
	 * @param harness     the child's harness identifier
	 * @param report      what the child's harness's runner reports
	 * @param runFolder   the child's run folder
	 * @param deniedReads the absolute paths that the launch denies the child
	 * @param bound       the parent's policy
	 * @return the child's needs
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 * @throws IOException              if a symbolic link in a login's path cannot be read
	 * @throws PolicyRefusal            if the parent's policy denies reading a login
	 */
	public static ChildNeeds needs(String harness, ChildNeedsReport report, Path runFolder, List<Path> deniedReads,
		ParentPolicy bound) throws IOException, PolicyRefusal
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(report, "report").isNotNull();
		requireThat(runFolder, "runFolder").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(bound, "bound").isNotNull();
		for (Path login : report.logins())
		{
			if (!bound.mayRead(login))
			{
				throw new PolicyRefusal("its sandbox denies its commands reading " + login + ", the login that a " +
					"requested " + harness + " session copies into its run folder, where the commands could read it",
					"Let the session's sandbox read " + login + ", then start a new session");
			}
		}
		return new ChildNeeds(runFolder, report.writes(), deniedReads, report.hosts());
	}

	/**
	 * Waits for the runner to exit with status {@code 0}.
	 *
	 * @param harness   the child's harness identifier
	 * @param process   the runner
	 * @param directory the request's directory, which holds the runner's error output
	 * @throws IOException if the runner does not exit in time, or exits with another status
	 */
	private static void awaitSuccess(String harness, Process process, Path directory) throws IOException
	{
		try
		{
			if (!process.waitFor(REPORT_WAIT.toMillis(), TimeUnit.MILLISECONDS))
			{
				process.destroyForcibly();
				throw new IOException("The " + harness + " runner did not report what its session needs within " +
					REPORT_WAIT.toSeconds() + " seconds");
			}
		}
		catch (InterruptedException e)
		{
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while the " + harness + " runner reported what its session needs", e);
		}
		if (process.exitValue() != 0)
		{
			throw new IOException("The " + harness + " runner could not report what its session needs (exit " +
				process.exitValue() + "): " + stderrTail(directory));
		}
	}

	/**
	 * Returns the end of the runner's error output.
	 *
	 * @param directory the request's directory
	 * @return at most the last {@value #STDERR_TAIL_BYTES} bytes, stripped
	 * @throws IOException if the output cannot be read
	 */
	private static String stderrTail(Path directory) throws IOException
	{
		Path stderr = directory.resolve(SessionLauncherChannel.RUNNER_STDERR_FILE);
		if (!Files.isRegularFile(stderr, LinkOption.NOFOLLOW_LINKS))
			return "";
		long size = Files.size(stderr);
		try (InputStream in = Files.newInputStream(stderr, LinkOption.NOFOLLOW_LINKS))
		{
			in.skipNBytes(Math.max(0, size - STDERR_TAIL_BYTES));
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
		}
	}
}
