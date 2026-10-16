package io.github.cowwoc.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.cowwoc.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Gives an assembled artifact's skills the wording of the platform that the artifact is built for. A skill's folder may
 * hold a {@code windows} folder of files worded for Windows, where Codex runs commands in PowerShell rather than bash.
 */
public final class SkillPlatformVariants
{
	/**
	 * The folder, inside a skill's folder, of the skill's files worded for Windows.
	 */
	private static final String WINDOWS_WORDINGS = "windows";

	/**
	 * Gives an artifact's skills the wording of a platform. On Windows, each file in a skill's {@code windows} folder
	 * replaces the skill's file of the same name, or joins the skill's files when the skill has no such file. On other
	 * platforms, the skill keeps its own files. The artifact then holds no {@code windows} folder in a skill.
	 *
	 * @param artifactDirectory the assembled artifact, whose skills are in its {@code skills} folder; an artifact
	 *                          without that folder is left as it is
	 * @param platform          the platform that the artifact is built for
	 * @throws NullPointerException if {@code artifactDirectory} or {@code platform} are null
	 * @throws IOException          if a skill's {@code windows} folder holds a folder, or a file cannot be moved or
	 *                              deleted; the skills that were handled before the failure keep their changes
	 */
	public static void select(Path artifactDirectory, Platform platform) throws IOException
	{
		requireThat(artifactDirectory, "artifactDirectory").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Path skills = artifactDirectory.resolve("skills");
		if (!Files.isDirectory(skills, NOFOLLOW_LINKS))
			return;
		List<Path> wordingFolders;
		try (Stream<Path> skillFolders = Files.list(skills))
		{
			wordingFolders = skillFolders.map(skill -> skill.resolve(WINDOWS_WORDINGS)).
				filter(folder -> Files.isDirectory(folder, NOFOLLOW_LINKS)).sorted().toList();
		}
		for (Path wordings : wordingFolders)
		{
			List<Path> files;
			try (Stream<Path> stream = Files.list(wordings))
			{
				files = stream.sorted().toList();
			}
			for (Path file : files)
			{
				if (Files.isDirectory(file, NOFOLLOW_LINKS))
					throw new IOException("A skill's " + WINDOWS_WORDINGS + " folder must hold only files: " + file);
				if (platform.isWindows())
					Files.move(file, wordings.getParent().resolve(file.getFileName()), REPLACE_EXISTING);
				else
					Files.delete(file);
			}
			Files.delete(wordings);
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private SkillPlatformVariants()
	{
	}
}
