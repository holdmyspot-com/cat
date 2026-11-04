package com.holdmyspot.cat.claude.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * Makes the file system refuse the first replacement of one existing file, as Windows refuses to replace a file that
 * another process holds open, and accept every later attempt.
 * <p>
 * The refusal starts when the replacer first reads its clock, which it does before its first attempt, and ends at its
 * first pause between attempts. On Windows the file is marked read-only; elsewhere its directory refuses writes.
 */
final class TransientRefusal
{
	private final Path target;
	private int pauses;
	private boolean refused;

	/**
	 * Creates a refusal.
	 *
	 * @param target the existing file whose replacement is refused once
	 * @throws IllegalArgumentException if {@code target} is not a regular file
	 * @throws IOException              if the file's kind cannot be read
	 */
	TransientRefusal(Path target) throws IOException
	{
		requireThat(target, "target").isRegularFile();
		this.target = target;
	}

	/**
	 * Returns a replacer that retries a refused replacement, as it does on Windows.
	 *
	 * @return the replacer
	 */
	FileReplacer replacer()
	{
		return new FileReplacer(Platform.WINDOWS_X64, this::pause, new RefusingClock());
	}

	/**
	 * Returns how many times the replacer paused after a refusal.
	 *
	 * @return the number of pauses
	 */
	int pauses()
	{
		return pauses;
	}

	/**
	 * Ends the refusal and counts the pause.
	 *
	 * @throws IOException if the file system cannot accept writes again
	 */
	private void pause() throws IOException
	{
		++pauses;
		refuse(false);
	}

	/**
	 * Starts or ends refusing the target's replacement.
	 *
	 * @param refusing {@code true} to refuse
	 * @throws IOException if the attribute or permissions cannot be changed
	 */
	private void refuse(boolean refusing) throws IOException
	{
		if (Platform.current().isWindows())
		{
			Files.getFileAttributeView(target, DosFileAttributeView.class).setReadOnly(refusing);
			return;
		}
		Set<PosixFilePermission> permissions;
		if (refusing)
			permissions = PosixFilePermissions.fromString("r-x------");
		else
			permissions = PosixFilePermissions.fromString("rwx------");
		Files.setPosixFilePermissions(target.getParent(), permissions);
	}

	/**
	 * Starts the refusal the first time the replacer reads the time.
	 */
	private final class RefusingClock extends Clock
	{
		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			throw new UnsupportedOperationException("The refusal's clock has one zone");
		}

		@Override
		public Instant instant()
		{
			if (!refused)
			{
				refused = true;
				try
				{
					refuse(true);
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			}
			return Instant.EPOCH;
		}
	}
}
