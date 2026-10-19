package com.holdmyspot.cat.common.git.replay;

import com.holdmyspot.cat.common.git.Git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures immutable whole-source or path-selected Git diffs beside a replay manifest.
 */
final class SourcePatches
{
	private final Git git;

	/**
	 * Creates a source-patch capture boundary.
	 *
	 * @param git Git access for the replay repository
	 */
	SourcePatches(Git git)
	{
		this.git = git;
	}

	/**
	 * Captures the selected paths from one declared source commit as its exact binary diff.
	 *
	 * @param manifestFile the manifest that owns the new patch file
	 * @param manifest the replay facts that declare the source
	 * @param source the selected source commit
	 * @param patchFile the new manifest-relative patch file
	 * @param paths the selected paths, or an empty list for the whole source
	 * @throws IOException if the source is not declared, a path or target is unsafe, or Git cannot render the diff
	 */
	void capture(Path manifestFile, BatchManifest manifest, String source, Path patchFile, List<String> paths)
		throws IOException
	{
		SourceSnapshot snapshot = manifest.sources().stream().filter(candidate -> candidate.hash().equals(source)).
			findFirst().orElseThrow(() -> new IOException("Topic-replay manifest does not contain source commit: " + source));
		Path manifestDirectory = manifestFile.toAbsolutePath().normalize().getParent();
		Path target = patchFile.toAbsolutePath().normalize();
		if (!target.startsWith(manifestDirectory))
			throw new IOException("Topic-replay patch file must be below the manifest directory: " + patchFile);
		if (Files.exists(target))
			throw new IOException("Topic-replay patch file already exists: " + target);
		for (String path : paths)
		{
			if (path == null || path.isBlank() || path.startsWith("/") || path.contains(".."))
			{
				throw new IOException("Topic-replay source patch paths must be non-blank, repository-relative, and not " +
					"contain '..': " + path);
			}
		}
		List<String> arguments = new ArrayList<>(List.of("diff", "--binary", snapshot.parentTree(), source));
		if (!paths.isEmpty())
		{
			arguments.add("--");
			arguments.addAll(paths);
		}
		Files.createDirectories(target.getParent());
		Files.write(target, git.bytes(arguments.toArray(String[]::new)));
	}
}
