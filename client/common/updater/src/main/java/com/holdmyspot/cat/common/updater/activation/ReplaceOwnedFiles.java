package com.holdmyspot.cat.common.updater.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.regex.PatternSyntaxException;

/**
 * Replaces the files that CAT owns in a directory that the harness shares with other files, such as the user's own
 * files, with the matching files of the validated release.
 * <p>
 * An ownership manifest lists the files of {@code directory} that CAT wrote. Only those files are CAT's to replace or
 * delete.
 * <p>
 * <b>Effect:</b> for each file {@code x} in the release's {@code sourceSubdirectory} whose name matches
 * {@code sourceGlob}, the core writes {@code <directory>/<prefix><x>} through a {@code .new} file in
 * {@code directory} and a rename. It refuses, before changing anything, to overwrite a file that exists and that the
 * ownership manifest does not list. It deletes each listed file that the new set lacks, and then rewrites the
 * ownership manifest to list the new set. Before changing a file or the manifest, it copies the old one to the
 * harness's updater state directory, under {@code previous-files/}.
 * <p>
 * <b>Undo:</b> the core restores each copied file and the old ownership manifest, and deletes each file that the step
 * created.
 * <p>
 * The {@code .new} files lie in {@code directory}, so each rename stays within one file system.
 *
 * @param directory          the directory that holds CAT's files beside other files
 * @param ownershipManifest  the file that lists the files of {@code directory} that CAT owns
 * @param prefix             the text that starts the name of each file that CAT writes to {@code directory}, such as
 *                           {@code cat-}
 * @param sourceSubdirectory the release's directory that holds the source files, relative to the release's top
 *                           directory and written with {@code /} between names, such as {@code agents}; it names a
 *                           path in the release archive, not on the local file system
 * @param sourceGlob         the glob that a source file's name matches, such as {@code *.toml}
 */
public record ReplaceOwnedFiles(Path directory, Path ownershipManifest, String prefix, String sourceSubdirectory,
	String sourceGlob) implements ActivationStep
{
	/**
	 * Creates a step.
	 *
	 * @param directory          the directory that holds CAT's files beside other files
	 * @param ownershipManifest  the file that lists the files of {@code directory} that CAT owns
	 * @param prefix             the text that starts the name of each file that CAT writes to {@code directory}, such
	 *                           as {@code cat-}
	 * @param sourceSubdirectory the release's directory that holds the source files, relative to the release's top
	 *                           directory and written with {@code /} between names, such as {@code agents}
	 * @param sourceGlob         the glob that a source file's name matches, such as {@code *.toml}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if either path is relative, contains a dot or dot-dot name, or is a root; the
	 *                                  prefix is blank or contains a slash or backslash; the source subdirectory is
	 *                                  empty, starts or ends with a slash, or contains a backslash, an empty name, or
	 *                                  a dot or dot-dot name; or the glob is blank, contains a slash or backslash, or
	 *                                  is not a valid glob
	 */
	public ReplaceOwnedFiles
	{
		ActivationPaths.requireAbsoluteNormalized(directory, "directory");
		ActivationPaths.requireAbsoluteNormalized(ownershipManifest, "ownershipManifest");
		requireThat(prefix, "prefix").isNotBlank().doesNotContain("/").doesNotContain("\\");
		requireThat(sourceSubdirectory, "sourceSubdirectory").isNotEmpty().doesNotContain("\\");
		for (String name : sourceSubdirectory.split("/", -1))
		{
			requireThat(name, "sourceSubdirectory.name").isNotEmpty().isNotEqualTo(".").isNotEqualTo("..");
		}
		requireThat(sourceGlob, "sourceGlob").isNotBlank().doesNotContain("/").doesNotContain("\\");
		try
		{
			FileSystems.getDefault().getPathMatcher("glob:" + sourceGlob);
		}
		catch (PatternSyntaxException e)
		{
			throw new IllegalArgumentException("sourceGlob is not a valid glob: " + sourceGlob, e);
		}
	}
}
