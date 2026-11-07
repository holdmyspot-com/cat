package com.holdmyspot.cat.common.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_B;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import com.holdmyspot.cat.common.fakes.test.Yielding;

/**
 * Tests {@link ToolScope}.
 */
public final class ToolScopeTest
{
	private static final String SESSION_ID = "ff46b68a-55dd-4224-89aa-065d419b8827";
	private static final String OTHER_SESSION_ID = "01a147d8-bf56-7283-92b4-12dcaeb53738";

	/**
	 * Creates the test fixture.
	 */
	public ToolScopeTest()
	{
	}

	/**
	 * Verifies a scope without a session has no CAT session directory and names no session variable.
	 *
	 * @throws IOException if the CAT session directory cannot be resolved
	 */
	@Test
	public void defaultScopeHasNoSession() throws IOException
	{
		try (ToolScope scope = new TestToolScope())
		{
			requireThat(scope.sessionDirectory(), "sessionDirectory").isEqualTo(Optional.empty());
			requireThat(scope.missingSession(), "missingSession").isEqualTo("No harness session variable is known");
		}
	}

	/**
	 * Verifies the CAT session directory is in the enclosing project root rather than a nested working directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void resolvesEnclosingProjectRoot() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-project-"));
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Files.createDirectories(root.resolve(".cat"));
			Path nestedDirectory = Files.createDirectories(root.resolve("client/common"));

			try (ToolScope scope = ToolScope.forSession(nestedDirectory, environment(HARNESS_A, SESSION_ID, home),
				List.of(HARNESS_A, HARNESS_B), Yielding.waiting(), GitProjects.IGNORING))
			{
				requireThat(scope.isInProject(), "isInProject").isTrue();
				SessionDirectory session = scope.requireSessionDirectory();
				requireThat(session.temp(), "temp").isEqualTo(root.toAbsolutePath().normalize().
					resolve(".cat/sessions/" + SESSION_ID + "/temp"));
			}
		}
	}

	/**
	 * Verifies that outside a CAT project the CAT session directory is in the working directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void outsideProjectUsesWorkingDirectory() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FileSystem isolated = FileSystems.newFileSystem(cleanup.add(Files.createTempDirectory("cat-outside-")).
				resolve("outside-project.zip"), Map.of("create", "true")))
		{
			Path root = isolated.getPath("/");
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			try (ToolScope scope = ToolScope.forSession(root, environment(HARNESS_B, SESSION_ID, home),
				List.of(HARNESS_A, HARNESS_B), Yielding.waiting(), GitProjects.IGNORING))
			{
				requireThat(scope.isInProject(), "isInProject").isFalse();
				requireThat(scope.requireSessionDirectory().directory(), "directory").
					isEqualTo(root.toAbsolutePath().normalize().resolve(".cat/sessions/" + SESSION_ID));
			}
		}
	}

	/**
	 * Verifies the harnesses are consulted in the order given when more than one session variable is set, and that
	 * repeated calls return the same directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void firstSetVariableNamesSession() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-project-"));
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Files.createDirectories(root.resolve(".cat"));
			Map<String, String> environment = Map.of(HARNESS_A.sessionVariable(), SESSION_ID,
				HARNESS_B.sessionVariable(), OTHER_SESSION_ID, "HOME", home.toString(), "USERPROFILE", home.toString());
			try (ToolScope scope = ToolScope.forSession(root, environment, List.of(HARNESS_B, HARNESS_A), Yielding.waiting(),
				GitProjects.IGNORING))
			{
				SessionDirectory session = scope.requireSessionDirectory();
				requireThat(session.sessionId(), "sessionId").isEqualTo(OTHER_SESSION_ID);
				requireThat(scope.requireSessionDirectory(), "again").isReferenceEqualTo(session, "session");
			}
		}
	}

	/**
	 * Verifies a session variable that is not a UUID in canonical form is refused.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesInvalidSessionVariable() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-project-"));
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Files.createDirectories(root.resolve(".cat"));
			try (ToolScope scope = ToolScope.forSession(root, environment(HARNESS_A, "session", home),
				List.of(HARNESS_A), Yielding.waiting(), GitProjects.IGNORING))
			{
				scope.sessionDirectory();
				throw new AssertionError("Accepted an invalid session variable");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("session");
			}
		}
	}

	/**
	 * Verifies that a tool run below the home folder, with no folder closer than the home folder that holds
	 * {@code .cat}, refuses to open a CAT session directory, because the folder that it takes as its project is the
	 * home folder, and that it creates nothing in the home folder.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesHomeFolderAsProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Files.createDirectories(home.resolve(".cat"));
			Path workingDirectory = Files.createDirectories(home.resolve("notes"));
			try (ToolScope scope = ToolScope.forSession(workingDirectory, environment(HARNESS_A, SESSION_ID, home),
				List.of(HARNESS_A), Yielding.waiting(), GitProjects.IGNORING))
			{
				scope.sessionDirectory();
				throw new AssertionError("Opened a CAT session directory in the home folder");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(home.toString()).contains("cannot be a CAT project").
					contains("Open a folder below the home folder");
			}
			requireThat(Files.exists(home.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies that a tool run in a project below the home folder, which holds its own {@code .cat}, opens its CAT
	 * session directory in that project even though the home folder also holds {@code .cat}.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void usesProjectBelowHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-home-")).toRealPath();
			Files.createDirectories(home.resolve(".cat"));
			Path project = Files.createDirectories(home.resolve("project"));
			Files.createDirectories(project.resolve(".cat"));
			Path workingDirectory = Files.createDirectories(project.resolve("module"));
			try (ToolScope scope = ToolScope.forSession(workingDirectory, environment(HARNESS_A, SESSION_ID, home),
				List.of(HARNESS_A), Yielding.waiting(), GitProjects.IGNORING))
			{
				requireThat(scope.requireSessionDirectory().directory(), "directory").
					isEqualTo(project.resolve(".cat/sessions/" + SESSION_ID));
			}
			requireThat(Files.exists(home.resolve(".cat/sessions")), "homeSessionsRootExists").isFalse();
		}
	}

	/**
	 * Returns an environment that sets one harness's session variable and the user's home directory.
	 *
	 * @param harness the harness
	 * @param sessionId the session variable's value
	 * @param home the user's home directory
	 * @return the environment
	 */
	private static Map<String, String> environment(SessionHarness harness, String sessionId, Path home)
	{
		return Map.of(harness.sessionVariable(), sessionId, "HOME", home.toString(), "USERPROFILE", home.toString());
	}
}
