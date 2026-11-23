package com.holdmyspot.cat.common.optimizer.cli;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Identifies the project files and user settings that select which prompt files a harness loads.
 *
 * @param files  the project-relative paths of loader files present in the source
 * @param digest the SHA-256 identity of the loader files and settings
 */
record LoaderIdentity(List<Path> files, String digest)
{
	private static final int LOADER_LINE_CAPACITY = 128;

	/**
	 * Reads one configured loader path in the source under inspection.
	 */
	@FunctionalInterface
	interface Entries
	{
		/**
		 * Reads a loader file.
		 *
		 * @param path its path relative to the source
		 * @return the file entry, or empty if the path contains no file
		 * @throws IOException if the file cannot be read
		 */
		Optional<FileEntry> entry(Path path) throws IOException;
	}

	/**
	 * Identifies the configured files and user settings without including configuration text in the result.
	 *
	 * @param paths        the project's possible loader file paths
	 * @param entries      reads files from the source under inspection
	 * @param userSettings the user's effective loader settings, or empty if none
	 * @return the loader identity
	 * @throws IOException if a file cannot be read
	 */
	static LoaderIdentity read(List<Path> paths, Entries entries, Optional<String> userSettings) throws IOException
	{
		List<Path> files = new ArrayList<>();
		StringBuilder lines = new StringBuilder(LOADER_LINE_CAPACITY * (paths.size() + 1));
		for (Path path : paths.stream().sorted().toList())
		{
			lines.append(PortablePaths.text(path)).append('=');
			Optional<FileEntry> entry = entries.entry(path);
			if (entry.isEmpty())
				lines.append("absent");
			else
			{
				files.add(path);
				switch (entry.get())
				{
					case FileEntry.RegularFile file -> lines.append(file.contentHash().hex());
					case FileEntry.SymbolicLink link -> lines.append("link:").append(PortablePaths.text(link.target()));
					case FileEntry.SpecialFile _ -> lines.append("special");
				}
			}
			lines.append('\n');
		}
		userSettings.ifPresent(settings -> lines.append("user settings=").append(settings).append('\n'));
		return new LoaderIdentity(List.copyOf(files), Sha256.of(lines.toString().getBytes(StandardCharsets.UTF_8)).hex());
	}
}
