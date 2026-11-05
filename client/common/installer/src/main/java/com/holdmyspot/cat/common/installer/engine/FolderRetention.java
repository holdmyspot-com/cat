package com.holdmyspot.cat.common.installer.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * What an install left of the version folders that the harness no longer needs.
 *
 * @param notices       a sentence for each folder that was left and for a record of the versions that could not be
 *                      read or written; empty when every unneeded folder was deleted
 * @param exitDeletions the running installer's folder when the harness would run it instead of the active version's,
 *                      which is deleted once the installer no longer needs its own files
 */
public record FolderRetention(List<String> notices, List<Path> exitDeletions)
{
	/**
	 * Creates what was left.
	 *
	 * @param notices       the sentences about folders that were left
	 * @param exitDeletions the folders to delete once the installer no longer needs its own files
	 * @throws NullPointerException if any of the arguments are null
	 */
	public FolderRetention
	{
		notices = List.copyOf(notices);
		exitDeletions = List.copyOf(exitDeletions);
	}

	/**
	 * Appends the notices to an install's message.
	 *
	 * @param message the install's message
	 * @return the message, followed by any sentences about folders that were left
	 */
	public String append(String message)
	{
		if (notices.isEmpty())
			return message;
		return message + " " + String.join(" ", notices);
	}
}
