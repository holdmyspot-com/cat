package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.HomeProject;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that {@link HomeProject} refuses the user's home folder as a CAT project and allows every other folder.
 */
public final class HomeProjectTest
{
	/**
	 * Creates the test fixture.
	 */
	public HomeProjectTest()
	{
	}

	/**
	 * Verifies that the home folder itself is refused, with a message that names it and tells the user to open a
	 * folder below it.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesHomeFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("home-project-"));

			Optional<String> refusal = HomeProject.refusal(home, home);

			requireThat(refusal, "refusal").isPresent();
			requireThat(refusal.get(), "message").contains(home.toString()).
				contains("cannot be a CAT project").contains("Open a folder below it");
		}
	}

	/**
	 * Verifies that a symbolic link to the home folder is refused, because its real path is the home folder, and that
	 * the home folder is refused when the home variable names it through a symbolic link.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesLinkToHomeFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("home-project-"));
			Path home = Files.createDirectory(root.resolve("home"));
			Path link = Files.createSymbolicLink(root.resolve("link"), home);

			requireThat(HomeProject.refusal(link, home), "linkRefusal").isPresent();
			requireThat(HomeProject.refusal(home, link), "linkedHomeRefusal").isPresent();
		}
	}

	/**
	 * Verifies that a folder below the home folder is allowed.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void allowsFolderBelowHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("home-project-"));
			Path project = Files.createDirectory(home.resolve("project"));

			requireThat(HomeProject.refusal(project, home), "refusal").isEmpty();
		}
	}

	/**
	 * Verifies that a folder that contains the home folder is allowed.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void allowsFolderAboveHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("home-project-"));
			Path home = Files.createDirectory(project.resolve("home"));

			requireThat(HomeProject.refusal(project, home), "refusal").isEmpty();
		}
	}
}
