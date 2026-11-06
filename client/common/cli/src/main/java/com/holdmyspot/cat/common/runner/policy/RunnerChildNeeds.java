package com.holdmyspot.cat.common.runner.policy;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
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

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Learns a child's needs from the runner of the child's harness, which alone knows the harness's model host, its
 * dependency caches and the login that its isolated home copies. The runner runs with
 * {@link ChildNeedsReport#OPTION} in a private folder inside the run folder, writes {@link ChildNeedsReport#FILE_NAME}
 * there, and the folder is deleted once the report is read.
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
	 * The child needs its run folder, the launch's denied reads, and the writes and hosts that its harness's runner
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
		Path absoluteRunFolder = runFolder.toAbsolutePath();
		Files.createDirectories(absoluteRunFolder);
		Path directory = Files.createTempDirectory(absoluteRunFolder, ".child-needs-");
		try
		{
			Path file = directory.resolve(ChildNeedsReport.FILE_NAME);
			try (Process process = starter.start(harness, List.of(ChildNeedsReport.OPTION, file.toString()),
				directory))
			{
				awaitSuccess(harness, process, directory);
			}
			return ChildNeedsReport.read(json, file).needs(harness, runFolder, deniedReads, bound);
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(directory);
		}
	}

	/**
	 * Waits for the runner to exit with status {@code 0}.
	 *
	 * @param harness   the child's harness identifier
	 * @param process   the runner
	 * @param directory the runner's folder, which holds its error output
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
	 * @param directory the runner's folder
	 * @return at most the last {@value #STDERR_TAIL_BYTES} bytes, stripped
	 * @throws IOException if the output cannot be read
	 */
	private static String stderrTail(Path directory) throws IOException
	{
		Path stderr = directory.resolve(RequestedRunner.STDERR_FILE);
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
