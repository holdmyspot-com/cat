package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.test.GitQueriesContract;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the {@link GitQueriesContract} against {@link GitProjects}, so that the answers that session-directory tests
 * use in place of Git are the ones that {@code SessionIgnoreTest} shows the questions that run Git give.
 */
public final class GitProjectsContractTest
{
	/**
	 * Creates the test.
	 */
	public GitProjectsContractTest()
	{
	}

	/**
	 * Verifies that the answers of {@link GitProjects} for a project in each situation are Git's.
	 *
	 * @throws IOException if the project directory cannot be created
	 */
	@Test
	public void answersAsGit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-git-projects-"));

			new GitQueriesContract((_, situation) -> switch (situation)
			{
				case IGNORING -> GitProjects.IGNORING;
				case NOT_IGNORING -> GitProjects.NOT_IGNORING;
				case FAILING_CHECK -> GitProjects.reporting(128, true);
				case BARE_REPOSITORY -> GitProjects.reporting(128, false);
			}).answersAsGit(project);
		}
	}
}
