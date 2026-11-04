package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.installer.engine.RuntimeSwap.Operation;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes the swap request with which an operation hands the replacement of the installation's Java runtime
 * to the stable launcher, and the result that the launcher prints once the replacement is finished. The request has
 * one value per line, so that the launcher can read it with shell builtins: the operation, the version that
 * {@code current} selects, the version that it selects afterwards, the process identifier and start instant of the
 * launcher that finishes the replacement, and the plugin version before an update ({@code -} for an absent value).
 * The launcher reads only the first four lines.
 */
final class SwapFiles
{
	/**
	 * The largest request, which holds three versions, a process identifier and an instant.
	 */
	private static final int MAXIMUM_BYTES = 1024;
	/**
	 * The number of lines of a request.
	 */
	private static final int LINES = 6;
	/**
	 * The text of an absent value.
	 */
	private static final String ABSENT = "-";
	private final Path request;
	private final Path result;

	/**
	 * Creates the swap files of an installation.
	 *
	 * @param data the installation's absolute, normalized data directory
	 */
	SwapFiles(Path data)
	{
		this.request = data.resolve(Bundle.SWAP_REQUEST);
		this.result = data.resolve(Bundle.SWAP_RESULT);
	}

	/**
	 * Returns the request's path.
	 *
	 * @return the path
	 */
	Path request()
	{
		return request;
	}

	/**
	 * Reads the pending request.
	 *
	 * @return the request, or empty when none is pending
	 * @throws IOException if the request is a link, unreadable or malformed
	 */
	Optional<Recorded> read() throws IOException
	{
		StateFiles.requireNoLinks(request);
		if (!Files.exists(request, NOFOLLOW_LINKS))
			return Optional.empty();
		String text = BoundedInput.readUtf8(request, MAXIMUM_BYTES, "The runtime swap request");
		List<String> lines = text.lines().toList();
		if (lines.size() != LINES || !text.endsWith("\n"))
			throw malformed();
		try
		{
			Operation operation = switch (lines.get(0))
			{
				case "update" -> Operation.UPDATE;
				default -> throw malformed();
			};
			RuntimeSwap swap = new RuntimeSwap(operation, ReleaseVersion.parse(lines.get(1)).toString(),
				ReleaseVersion.parse(lines.get(2)).toString());
			long launcher = Long.parseLong(lines.get(3));
			Optional<Instant> start = Optional.empty();
			if (!lines.get(4).equals(ABSENT))
				start = Optional.of(Instant.parse(lines.get(4)));
			Optional<String> pluginVersion = Optional.empty();
			if (!lines.get(5).equals(ABSENT))
			{
				InstalledVersions.release(lines.get(5));
				pluginVersion = Optional.of(lines.get(5));
			}
			return Optional.of(new Recorded(swap, launcher, start, pluginVersion));
		}
		catch (TrustException | DateTimeParseException | IllegalArgumentException e)
		{
			throw new IOException(malformed().getMessage(), e);
		}
	}

	/**
	 * Records a request that the stable launcher which started this installer's JVM finishes.
	 *
	 * @param swap          the replacement
	 * @param pluginVersion the plugin version before an update, or empty
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the request cannot be written
	 */
	void write(RuntimeSwap swap, Optional<String> pluginVersion) throws IOException
	{
		requireThat(swap, "swap").isNotNull();
		requireThat(pluginVersion, "pluginVersion").isNotNull();
		// The stable launcher runs the installer's JVM as its child and finishes the replacement after the JVM exits
		Optional<ProcessHandle> launcher = ProcessHandle.current().parent();
		String pid = launcher.map(handle -> Long.toString(handle.pid())).orElse("0");
		String start = launcher.flatMap(handle -> handle.info().startInstant()).map(Instant::toString).
			orElse(ABSENT);
		String text = swap.operation().id() + "\n" + swap.fromVersion() + "\n" + swap.toVersion() + "\n" + pid +
			"\n" + start + "\n" + pluginVersion.orElse(ABSENT) + "\n";
		StateFiles.write(request, text.getBytes(UTF_8));
	}

	/**
	 * Deletes the request and the saved result.
	 *
	 * @throws IOException if either cannot be deleted
	 */
	void delete() throws IOException
	{
		StateFiles.requireNoLinks(request);
		Files.deleteIfExists(request);
		Files.deleteIfExists(result);
	}

	/**
	 * Creates the failure of a malformed request.
	 *
	 * @return the failure
	 */
	private IOException malformed()
	{
		return new IOException("The runtime swap request " + request + " is malformed; rerun the entry command to " +
			"repair the installation.");
	}

	/**
	 * A pending request.
	 *
	 * @param swap          the replacement
	 * @param launcher      the process identifier of the launcher that finishes it, or zero when none was known
	 * @param launcherStart the launcher's start instant, or empty when it was not known
	 * @param pluginVersion the plugin version before an update, or empty
	 */
	record Recorded(RuntimeSwap swap, long launcher, Optional<Instant> launcherStart, Optional<String> pluginVersion)
	{
		/**
		 * Indicates whether the launcher that is to finish the replacement still runs. A process that reuses its
		 * identifier is told apart by its start instant.
		 *
		 * @return true if it runs
		 */
		boolean launcherRunning()
		{
			return launcher > 0 && ProcessHandle.of(launcher).filter(ProcessHandle::isAlive).
				filter(handle -> launcherStart.isEmpty() || handle.info().startInstant().equals(launcherStart)).
				isPresent();
		}
	}
}
