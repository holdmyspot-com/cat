package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Atomically replaces a file with another file in the same directory.
 * <p>
 * Windows refuses to replace a file while any process has it open, so a reader that polls the file, such as a session
 * that watches a run's manifest, can make a replacement fail. There, the replacer tries again until
 * {@link #RETRY_LIMIT} passes; a reader holds the file only while it reads it. Elsewhere, a refusal fails at once.
 * <p>
 * This class is thread-safe.
 */
public final class FileReplacer
{
	/**
	 * How long a replacement keeps trying while Windows refuses it.
	 */
	private static final Duration RETRY_LIMIT = Duration.ofSeconds(5);
	/**
	 * How long {@link #retrying(Waiting)}'s replacer waits between attempts.
	 */
	private static final Duration RETRY_INTERVAL = Duration.ofMillis(10);
	private final Platform platform;
	private final Pause pause;
	private final Clock clock;

	/**
	 * Returns a replacer for the current platform that waits 10 milliseconds between attempts and measures its limit
	 * with the system clock.
	 *
	 * @param waiting how the replacer waits between attempts
	 * @return the replacer
	 * @throws NullPointerException if {@code waiting} is null
	 */
	public static FileReplacer retrying(Waiting waiting)
	{
		requireThat(waiting, "waiting").isNotNull();
		return new FileReplacer(Platform.current(), waiting.every(RETRY_INTERVAL), Clock.systemUTC());
	}

	/**
	 * Creates a replacer.
	 *
	 * @param platform the platform whose file system the replacer writes; only Windows refuses a replacement that a
	 *                 later attempt can complete
	 * @param pause    waits between attempts
	 * @param clock    measures how long a replacement has been trying
	 * @throws NullPointerException if any of the arguments are null
	 */
	public FileReplacer(Platform platform, Pause pause, Clock clock)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(pause, "pause").isNotNull();
		requireThat(clock, "clock").isNotNull();
		this.platform = platform;
		this.pause = pause;
		this.clock = clock;
	}

	/**
	 * Atomically replaces a file with another file in the same directory.
	 *
	 * @param source the file that takes the target's place
	 * @param target the file to replace
	 * @throws NullPointerException                          if any of the arguments are null
	 * @throws java.nio.file.AtomicMoveNotSupportedException if the file system cannot replace the file atomically
	 * @throws AccessDeniedException                         if the file system refuses the replacement, on Windows
	 *                                                       for longer than the retry limit
	 * @throws IOException                                   if the file cannot be replaced, or the pause between
	 *                                                       attempts fails
	 */
	public void replace(Path source, Path target) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(target, "target").isNotNull();
		Instant deadline = clock.instant().plus(RETRY_LIMIT);
		while (true)
		{
			try
			{
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				return;
			}
			catch (AccessDeniedException e)
			{
				if (!platform.isWindows() || !clock.instant().isBefore(deadline))
					throw e;
				try
				{
					pause.pause();
				}
				catch (IOException failure)
				{
					failure.addSuppressed(e);
					throw failure;
				}
			}
		}
	}
}
