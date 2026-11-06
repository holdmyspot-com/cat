package com.holdmyspot.cat.common.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_B;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.TestHookScope;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.holdmyspot.cat.common.fakes.test.Yielding;

/**
 * Tests {@link SessionDirectory}.
 */
public final class SessionDirectoryTest
{
	private static final String SESSION_ID = "ff46b68a-55dd-4224-89aa-065d419b8827";
	private static final Instant CREATED = Instant.parse("2026-10-16T12:00:00Z");

	/**
	 * Creates a test instance.
	 */
	public SessionDirectoryTest()
	{
	}

	/**
	 * Verifies a hook obtains its session's directory from the hook input's {@code session_id}: the directory, its
	 * {@code temp} directory and a marker that names the harness and its configuration directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void hookInputNamesDirectory() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-session-"));
			Path plugin = Files.createDirectories(root.resolve("plugin"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path configuration = root.resolve("configuration");
			JsonMapper json = JsonMapper.builder().build();
			JsonNode event = json.readTree("{\"session_id\":\"" + SESSION_ID + "\"}");
			try (HookScope scope = new TestHookScope(plugin, root.resolve("home"), HARNESS_A, configuration))
			{
				SessionDirectory session = scope.sessionDirectory(project, event, "session_id");

				Path directory = project.resolve(".cat/sessions/" + SESSION_ID);
				requireThat(session.directory(), "directory").isEqualTo(directory);
				requireThat(session.temp(), "temp").isEqualTo(directory.resolve("temp"));
				requireThat(session.temp(), "temp").isDirectory();
				JsonNode marker = json.readTree(Files.readString(directory.resolve("session.json"), UTF_8));
				requireThat(marker.path("harness").stringValue(), "harness").isEqualTo("harness-a");
				requireThat(marker.path("configuration_directory").stringValue(), "configurationDirectory").
					isEqualTo(configuration.toAbsolutePath().normalize().toString());
				Instant created = Instant.parse(marker.path("created_at").stringValue());
				requireThat(created, "created").isLessThanOrEqualTo(Instant.now());
			}
		}
	}

	/**
	 * Verifies a command obtains its session's directory, in the nearest CAT project, from the session variable of
	 * either harness, and that the marker records the harness whose variable is set and that harness's default
	 * configuration directory in the user's home directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void sessionVariableNamesDirectory() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-session-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.createDirectories(project.resolve(".cat"));
			Path nested = Files.createDirectories(project.resolve("client/common"));
			Path home = root.resolve("home");
			JsonMapper json = JsonMapper.builder().build();
			String otherId = "01a147d8-bf56-7283-92b4-12dcaeb53738";
			for (Map.Entry<String, String> variable : Map.of(HARNESS_A.sessionVariable(), SESSION_ID,
				HARNESS_B.sessionVariable(), otherId).entrySet())
			{
				Map<String, String> environment = Map.of(variable.getKey(), variable.getValue(), "HOME", home.toString(),
					"USERPROFILE", home.toString());
				try (ToolScope scope = ToolScope.forSession(nested, environment, List.of(HARNESS_A, HARNESS_B),
					Yielding.waiting(), GitProjects.IGNORING))
				{
					SessionDirectory session = scope.sessionDirectory().orElseThrow();

					Path directory = project.resolve(".cat/sessions/" + variable.getValue());
					requireThat(session.directory(), "directory").isEqualTo(directory);
					requireThat(session.temp(), "temp").isDirectory();
					JsonNode marker = json.readTree(Files.readString(directory.resolve("session.json"), UTF_8));
					String harness = "harness-a";
					Path configuration = home.resolve(".harness-a");
					if (variable.getKey().equals(HARNESS_B.sessionVariable()))
					{
						harness = "harness-b";
						configuration = home.resolve(".harness-b");
					}
					requireThat(marker.path("harness").stringValue(), "harness").isEqualTo(harness);
					requireThat(marker.path("configuration_directory").stringValue(), "configurationDirectory").
						isEqualTo(configuration.toAbsolutePath().normalize().toString());
				}
			}
		}
	}

	/**
	 * Verifies the marker records the configuration directory that the harness's configuration variable names.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void configurationVariableNamesDirectory() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-session-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.createDirectories(project.resolve(".cat"));
			Path configuration = root.resolve("configured");
			Map<String, String> environment = Map.of(HARNESS_A.sessionVariable(), SESSION_ID,
				HARNESS_A.configurationVariable(), configuration.toString());
			try (ToolScope scope = ToolScope.forSession(project, environment, List.of(HARNESS_A, HARNESS_B),
				Yielding.waiting(), GitProjects.IGNORING))
			{
				SessionDirectory session = scope.sessionDirectory().orElseThrow();
				JsonNode marker = JsonMapper.builder().build().readTree(Files.readString(session.marker(), UTF_8));
				requireThat(marker.path("configuration_directory").stringValue(), "configurationDirectory").
					isEqualTo(configuration.toAbsolutePath().normalize().toString());
			}
		}
	}

	/**
	 * Verifies a session ID that is not a canonical UUID is refused, so that a session directory never takes the name
	 * of another entry of the directory that holds the CAT session directories.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesNonCanonicalSessionId() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			JsonMapper json = JsonMapper.builder().build();
			for (String sessionId : List.of("temp", "FF46B68A-55DD-4224-89AA-065D419B8827", "1-2-3-4-5", "../escape"))
			{
				try
				{
					GitProjects.openSession(project, sessionId, HARNESS_A, project.resolve("configuration"), CREATED,
						json);
					throw new AssertionError("Accepted session ID: " + sessionId);
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains(sessionId);
				}
			}
			try (Stream<Path> entries = Files.list(project))
			{
				requireThat(entries.toList(), "entries").isEmpty();
			}
		}
	}

	/**
	 * Verifies that concurrent first uses of one session directory leave exactly one complete marker and no partial
	 * file.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if a first use fails
	 */
	@Test
	public void concurrentFirstUsesLeaveOneMarker() throws IOException, InterruptedException, ExecutionException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService executor = Executors.newFixedThreadPool(8))
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			Path configuration = project.resolve("configuration");
			JsonMapper json = JsonMapper.builder().build();
			List<Callable<SessionDirectory>> uses = new ArrayList<>();
			for (int index = 0; index < 16; ++index)
			{
				Instant created = CREATED.plusSeconds(index);
				uses.add(() -> GitProjects.openSession(project, SESSION_ID, HARNESS_A, configuration, created, json));
			}
			for (Future<SessionDirectory> use : executor.invokeAll(uses))
				use.get();

			Path directory = project.resolve(".cat/sessions/" + SESSION_ID);
			try (Stream<Path> entries = Files.list(directory))
			{
				requireThat(entries.map(entry -> entry.getFileName().toString()).sorted().toList(), "entries").
					isEqualTo(List.of("session.json", "temp"));
			}
			JsonNode marker = json.readTree(Files.readString(directory.resolve("session.json"), UTF_8));
			requireThat(marker.path("harness").stringValue(), "harness").isEqualTo("harness-a");
			Instant created = Instant.parse(marker.path("created_at").stringValue());
			requireThat(created, "created").isGreaterThanOrEqualTo(CREATED).isLessThan(CREATED.plusSeconds(16));
		}
	}

	/**
	 * Verifies a later use of a session directory keeps the marker that its first use wrote.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void keepsExistingMarker() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			JsonMapper json = JsonMapper.builder().build();
			SessionDirectory first = GitProjects.openSession(project, SESSION_ID, HARNESS_A,
				project.resolve("configuration"), CREATED, json);
			String marker = Files.readString(first.marker(), UTF_8);

			GitProjects.openSession(project, SESSION_ID, HARNESS_B, project.resolve("other"),
				CREATED.plusSeconds(3600), json);

			requireThat(Files.readString(first.marker(), UTF_8), "marker").isEqualTo(marker);
		}
	}

	/**
	 * Verifies a command without either session variable has no session directory, and that the refusal of a command
	 * that needs one names both variables.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void refusesWithoutSessionVariable() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			Files.createDirectories(project.resolve(".cat"));
			try (ToolScope scope = ToolScope.forSession(project, Map.of("HOME", project.toString()),
				List.of(HARNESS_A, HARNESS_B), Yielding.waiting(), GitProjects.IGNORING))
			{
				requireThat(scope.sessionDirectory(), "sessionDirectory").isEqualTo(Optional.empty());
				try
				{
					scope.requireSessionDirectory();
					throw new AssertionError("A scope without a session variable returned a session directory");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").contains(HARNESS_A.sessionVariable()).
						contains(HARNESS_B.sessionVariable());
				}
			}
			requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
		}
	}

	/**
	 * Verifies the paths of the rules files inside a session directory and of another session's rules file, which naming
	 * it does not create, and that the session ID replaces the rule texts' stand-in.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void providesRulesFiles() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			SessionDirectory session = GitProjects.openSession(project, SESSION_ID, HARNESS_A,
				project.resolve("configuration"), CREATED, JsonMapper.builder().build());

			requireThat(session.rulesFile(), "rulesFile").isEqualTo(session.directory().resolve("rules.md"));
			requireThat(session.subagentRulesFile("ab8e622242de0738a"), "subagentRulesFile").
				isEqualTo(session.directory().resolve("subagents/ab8e622242de0738a.md"));
			String otherSession = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
			requireThat(session.rulesFileOf(otherSession), "rulesFileOf").
				isEqualTo(session.directory().resolveSibling(otherSession).resolve("rules.md"));
			requireThat(Files.exists(session.directory().resolveSibling(otherSession)), "otherSessionExists").isFalse();
			requireThat(session.insertSessionId("work/" + SessionDirectory.SESSION_ID_DIRECTIVE + "/temp"), "text").
				isEqualTo("work/" + SESSION_ID + "/temp");
		}
	}

	/**
	 * Verifies a session's campaigns live in its {@code optimizer} directory and its runs in
	 * {@code optimizer-runs/<harness>}, and that naming them creates neither.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void providesCampaignPaths() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			SessionDirectory session = GitProjects.openSession(project, SESSION_ID, HARNESS_A,
				project.resolve("configuration"), CREATED, JsonMapper.builder().build());

			Path directory = project.toAbsolutePath().normalize().resolve(".cat/sessions/" + SESSION_ID);
			requireThat(session.campaigns(), "campaigns").isEqualTo(directory.resolve("optimizer"));
			requireThat(session.runRoot("claude"), "runRoot").isEqualTo(directory.resolve("optimizer-runs/claude"));
			requireThat(Files.exists(session.campaigns()), "campaignsExist").isFalse();
			requireThat(Files.exists(directory.resolve("optimizer-runs")), "runsExist").isFalse();
		}
	}

	/**
	 * Verifies a session lists the campaign directory of every session of the project, its own included, by session
	 * ID, skips entries that are not session directories, and creates nothing.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void listsCampaignsOfEverySession() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			JsonMapper json = JsonMapper.builder().build();
			String other = "01a147d8-bf56-7283-92b4-12dcaeb53738";
			SessionDirectory otherSession = GitProjects.openSession(project, other, HARNESS_B,
				project.resolve("configuration-b"), CREATED, json);
			SessionDirectory session = GitProjects.openSession(project, SESSION_ID, HARNESS_A,
				project.resolve("configuration"), CREATED, json);
			Files.createDirectories(session.directory().resolveSibling("temp"));
			Files.createDirectories(session.directory().resolveSibling("optimizer/20261015-0900-abc123"));

			Map<String, Path> campaigns = session.campaignsBySession();

			requireThat(campaigns, "campaigns").isEqualTo(Map.of(other, otherSession.campaigns(), SESSION_ID,
				session.campaigns()));
			requireThat(new ArrayList<>(campaigns.keySet()), "order").isEqualTo(List.of(other, SESSION_ID));
			requireThat(Files.exists(otherSession.campaigns()), "otherCampaignsExist").isFalse();
		}
	}

	/**
	 * Verifies a subagent ID that cannot be a file name is refused.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*\\.\\./escape.*")
	public void refusesUnsafeSubagentId() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-session-"));
			SessionDirectory session = GitProjects.openSession(project, SESSION_ID, HARNESS_A,
				project.resolve("configuration"), CREATED, JsonMapper.builder().build());
			session.subagentRulesFile("../escape");
		}
	}
}
