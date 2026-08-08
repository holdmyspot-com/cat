package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts one numbered source-diff hunk with the Git header required to apply it.
 */
final class SourceHunks
{
	private final Git git;

	/**
	 * Creates a source-hunk extraction boundary.
	 *
	 * @param git Git access for the replay repository
	 */
	SourceHunks(Git git)
	{
		this.git = git;
	}

	/**
	 * Returns the diff bytes for one numbered source hunk, including the Git header that identifies its file version.
	 *
	 * @param manifest the manifest containing the source commit
	 * @param source the source commit containing the hunk
	 * @param path the source path containing the hunk
	 * @param hunkIndex the one-based hunk number
	 * @return the source hunk patch
	 * @throws IOException if the manifest does not contain the source or Git cannot render the requested hunk
	 */
	String extract(BatchManifest manifest, String source, String path, int hunkIndex) throws IOException
	{
		SourceSnapshot snapshot = manifest.sources().stream().filter(candidate -> candidate.hash().equals(source)).
			findFirst().orElseThrow(() -> new IOException("Topic-replay manifest does not contain source commit: " + source));
		String diff = new String(git.bytes("diff", "--binary", snapshot.parentTree(), source, "--", path), UTF_8);
		List<String> hunks = Arrays.stream(diff.split("(?m)(?=^@@ )", -1)).toList();
		if (hunks.size() <= hunkIndex || !hunks.get(hunkIndex).startsWith("@@ "))
			throw new IOException("Topic-replay source hunk index is unavailable for " + path + ": " + hunkIndex);
		int firstHunk = diff.indexOf("@@ ");
		return diff.substring(0, firstHunk) + hunks.get(hunkIndex);
	}

	/**
	 * Writes one extracted source hunk to a prepared manifest-local patch file.
	 *
	 * @param manifest the manifest containing the source commit
	 * @param source the source commit containing the hunk
	 * @param patchFile the new patch file that receives the extracted hunk
	 * @param path the source path containing the hunk
	 * @param hunkIndex the one-based hunk number
	 * @throws IOException if the source or hunk cannot be rendered or the patch cannot be written
	 */
	void capture(BatchManifest manifest, String source, Path patchFile, String path, int hunkIndex) throws IOException
	{
		Files.writeString(patchFile, extract(manifest, source, path, hunkIndex), UTF_8);
	}
}
