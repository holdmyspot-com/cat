package io.github.cowwoc.cat.common.test;

import static io.github.cowwoc.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.cat.common.test.SessionHarnesses.HARNESS_B;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.SessionHarness;
import io.github.cowwoc.cat.common.SessionDirectory;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

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
		try (ToolScope scope = new ToolScope())
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-project-"));
			Files.createDirectories(root.resolve(".cat"));
			Path nestedDirectory = Files.createDirectories(root.resolve("client/common"));

			try (ToolScope scope = ToolScope.forSession(nestedDirectory, environment(HARNESS_A, SESSION_ID, root),
				List.of(HARNESS_A, HARNESS_B)))
			{
				requireThat(scope.isInProject(), "isInProject").isTrue();
				SessionDirectory session = scope.requireSessionDirectory();
				requireThat(session.temp(), "temp").isEqualTo(root.toAbsolutePath().normalize().
					resolve(".cat/work/" + SESSION_ID + "/temp"));
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-outside-"));
			try (ToolScope scope = ToolScope.forSession(root, environment(HARNESS_B, SESSION_ID, root),
				List.of(HARNESS_A, HARNESS_B)))
			{
				requireThat(scope.isInProject(), "isInProject").isFalse();
				requireThat(scope.requireSessionDirectory().directory(), "directory").
					isEqualTo(root.toAbsolutePath().normalize().resolve(".cat/work/" + SESSION_ID));
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-project-"));
			Files.createDirectories(root.resolve(".cat"));
			Map<String, String> environment = Map.of(HARNESS_A.sessionVariable(), SESSION_ID,
				HARNESS_B.sessionVariable(), OTHER_SESSION_ID, "HOME", root.toString(), "USERPROFILE", root.toString());
			try (ToolScope scope = ToolScope.forSession(root, environment, List.of(HARNESS_B, HARNESS_A)))
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-project-"));
			try (ToolScope scope = ToolScope.forSession(root, environment(HARNESS_A, "session", root),
				List.of(HARNESS_A)))
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
