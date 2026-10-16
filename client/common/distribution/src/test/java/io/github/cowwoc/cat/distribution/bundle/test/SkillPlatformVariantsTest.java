package io.github.cowwoc.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.distribution.bundle.SkillPlatformVariants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link SkillPlatformVariants}.
 */
public final class SkillPlatformVariantsTest
{
	/** Creates a test instance. */
	public SkillPlatformVariantsTest()
	{
	}

	/**
	 * Verifies that an artifact built for Windows holds each skill file's Windows wording in place of the file, and each
	 * Windows-only file beside the skill's other files, without the {@code windows} folder.
	 *
	 * @throws IOException if the skills cannot be written or read
	 */
	@Test
	public void usesWindowsWordingsOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = writeSkills(cleanup.add(Files.createTempDirectory("skill-variants-")));

			SkillPlatformVariants.select(artifact, Platform.WINDOWS_X64);

			Path skill = artifact.resolve("skills/runner");
			requireThat(Files.readString(skill.resolve("first-use.md")), "firstUse").isEqualTo("PowerShell\n");
			requireThat(Files.readString(skill.resolve("SKILL.md")), "skill").isEqualTo("routing\n");
			requireThat(Files.readString(skill.resolve("windows-only.md")), "windowsOnly").isEqualTo("only\n");
			requireThat(Files.exists(skill.resolve("windows")), "windows").isFalse();
		}
	}

	/**
	 * Verifies that an artifact built for another platform keeps each skill file's own wording and holds no
	 * {@code windows} folder.
	 *
	 * @throws IOException if the skills cannot be written or read
	 */
	@Test
	public void keepsOwnWordingsElsewhere() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = writeSkills(cleanup.add(Files.createTempDirectory("skill-variants-")));

			SkillPlatformVariants.select(artifact, Platform.LINUX_X64);

			Path skill = artifact.resolve("skills/runner");
			requireThat(Files.readString(skill.resolve("first-use.md")), "firstUse").isEqualTo("bash\n");
			requireThat(Files.exists(skill.resolve("windows-only.md")), "windowsOnly").isFalse();
			requireThat(Files.exists(skill.resolve("windows")), "windows").isFalse();
		}
	}

	/**
	 * Verifies that an artifact without skills is left as it is.
	 *
	 * @throws IOException if the artifact cannot be created or read
	 */
	@Test
	public void acceptsArtifactWithoutSkills() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("skill-variants-"));

			SkillPlatformVariants.select(artifact, Platform.WINDOWS_X64);

			requireThat(Files.exists(artifact.resolve("skills")), "skills").isFalse();
		}
	}

	/**
	 * Writes a skill whose {@code first-use.md} has a Windows wording, and a Windows-only file.
	 *
	 * @param artifact the artifact's folder
	 * @return {@code artifact}
	 * @throws IOException if the files cannot be written
	 */
	private static Path writeSkills(Path artifact) throws IOException
	{
		Path skill = Files.createDirectories(artifact.resolve("skills/runner/windows")).getParent();
		Files.writeString(skill.resolve("SKILL.md"), "routing\n");
		Files.writeString(skill.resolve("first-use.md"), "bash\n");
		Files.writeString(skill.resolve("windows/first-use.md"), "PowerShell\n");
		Files.writeString(skill.resolve("windows/windows-only.md"), "only\n");
		return artifact;
	}
}
