package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The session slots that CAT left untouched because the launch that they hold stayed uncertain after its owner was
 * gone. The list lives beside the campaign's lock file, so that every command that reports the campaign names the
 * slots until the user removes them.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class StuckSlots
{
	private static final String FILE_NAME = "stuck-slots.txt";

	/**
	 * Prevents instantiation.
	 */
	private StuckSlots()
	{
	}

	/**
	 * Records a slot that CAT left untouched. Recording a slot again changes nothing.
	 *
	 * @param lockFile the campaign's lock file
	 * @param slot     the slot's absolute path
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code slot} is not absolute or its name has a line break
	 * @throws IOException              if the record cannot be written
	 */
	public static void record(Path lockFile, Path slot) throws IOException
	{
		requireThat(lockFile, "lockFile").isNotNull();
		requireThat(slot, "slot").isAbsolute();
		String line = slot.toString();
		if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0)
			throw new IllegalArgumentException("The slot's path has a line break: " + line);
		if (read(lockFile).contains(line))
			return;
		Files.writeString(file(lockFile), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
			StandardOpenOption.APPEND);
	}

	/**
	 * Returns the notices for the recorded slots that still exist, which the user removes by hand.
	 *
	 * @param lockFile the campaign's lock file
	 * @return one notice for each recorded slot that still exists, in the order that the slots were recorded
	 * @throws NullPointerException if {@code lockFile} is null
	 * @throws IOException          if the record cannot be read
	 */
	public static List<String> notices(Path lockFile) throws IOException
	{
		requireThat(lockFile, "lockFile").isNotNull();
		List<String> notices = new ArrayList<>();
		for (String line : read(lockFile))
		{
			if (Files.exists(Path.of(line), LinkOption.NOFOLLOW_LINKS))
			{
				notices.add("CAT left the session slot " + line + " untouched, because the launch that it held never " +
					"recorded whether a harness process started and the process that launched it is gone. It ran " +
					"that attempt again in another slot. Remove the slot's directory by hand.");
			}
		}
		return notices;
	}

	/**
	 * Reads the recorded slots.
	 *
	 * @param lockFile the campaign's lock file
	 * @return the slots' paths, or none if nothing was recorded
	 * @throws IOException if the record cannot be read
	 */
	private static List<String> read(Path lockFile) throws IOException
	{
		try
		{
			return Files.readAllLines(file(lockFile), StandardCharsets.UTF_8).stream().
				filter(line -> !line.isBlank()).toList();
		}
		catch (NoSuchFileException _)
		{
			return List.of();
		}
	}

	/**
	 * Returns the file that lists the slots.
	 *
	 * @param lockFile the campaign's lock file
	 * @return the file
	 */
	private static Path file(Path lockFile)
	{
		return lockFile.resolveSibling(FILE_NAME);
	}
}
