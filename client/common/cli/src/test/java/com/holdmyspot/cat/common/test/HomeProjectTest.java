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
 * Verifies that {@link HomeProject} refuses the user's home folder and every folder that contains it as a CAT project,
 * and allows every other folder.
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
				contains("cannot be a CAT project").contains("Open a folder below the home folder");
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
	 * Verifies that a folder that contains the home folder is refused, with a message that names both folders.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesParentOfHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("home-project-"));
			Path home = Files.createDirectories(project.resolve("users/home"));

			Optional<String> refusal = HomeProject.refusal(project, home);

			requireThat(refusal, "refusal").isPresent();
			requireThat(refusal.get(), "message").contains(project.toString()).contains(home.toString()).
				contains("cannot be a CAT project");
		}
	}

	/**
	 * Verifies that the root of the filesystem that holds the home folder is refused, because it contains the home
	 * folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesFilesystemRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("home-project-"));

			requireThat(HomeProject.refusal(home.toRealPath().getRoot(), home), "refusal").isPresent();
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
	 * Verifies that a folder outside the home folder's ancestry is allowed, both one beside the home folder and one
	 * whose path is the start of the home folder's path as text but not one of its parent folders.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void allowsFolderOutsideHomeAncestry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("home-project-"));
			Path home = Files.createDirectory(root.resolve("home"));
			Path beside = Files.createDirectory(root.resolve("project"));
			Path textPrefix = Files.createDirectory(root.resolve("hom"));

			requireThat(HomeProject.refusal(beside, home), "besideRefusal").isEmpty();
			requireThat(HomeProject.refusal(textPrefix, home), "textPrefixRefusal").isEmpty();
		}
	}
}
