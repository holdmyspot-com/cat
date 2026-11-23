package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.claude.runner.ClaudeSessionRole;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies that a session that the optimizer starts with {@code --read-only-evidence} paths and a denied host project
 * can read that evidence and its own nested session, while the rest of the host project stays denied. The host project
 * holds the optimizer's owner session, whose responses directory holds the evidence, so the denial of the host project
 * that the request server's policy carries must not cover the evidence.
 */
public final class ClaudeReadOnlyEvidenceAccessTest
{
	/**
	 * Creates the test.
	 */
	public ClaudeReadOnlyEvidenceAccessTest()
	{
	}

	/**
	 * Verifies that a judge's settings deny its file tools none of its read-only evidence, and every other file of the
	 * host project that holds the evidence, including the other responses beside the evidence and the runner's records
	 * of its own nested session.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void judgeReadsEvidenceInDeniedHostProject() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(scope, cleanup, jvm.temporaryDirectory(), false);

			List<String> denials = fixture.denials(scope, ClaudeSessionRole.JUDGE.sandboxed(),
				ClaudeSessionRole.JUDGE.tools());

			fixture.requireEvidenceReadable(denials);
			fixture.requireRestOfHostProjectDenied(denials);
		}
	}

	/**
	 * Verifies that the settings of a session that runs in a sandbox, such as a prompt author, deny its file tools none
	 * of its read-only evidence, and every other file of the host project that holds the evidence.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void sandboxedSessionReadsEvidence() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(scope, cleanup, jvm.temporaryDirectory(), false);

			List<String> denials = fixture.denials(scope, true, List.of());

			fixture.requireEvidenceReadable(denials);
			fixture.requireRestOfHostProjectDenied(denials);
		}
	}

	/**
	 * Verifies that the settings of a session that may request sessions deny its file tools neither its read-only
	 * evidence, nor its responses directory and the read-only copies that its project's links lead to, which its own
	 * settings let it read, while the runner's records of its nested session and the rest of the host project stay
	 * denied.
	 *
	 * @throws IOException if a fixture cannot be created or read
	 */
	@Test
	public void sessionReadsItsNestedSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(scope, cleanup, jvm.temporaryDirectory(), true);

			List<String> denials = fixture.denials(scope, true, List.of());

			fixture.requireNestedSessionReadable(denials);
			fixture.requireRestOfHostProjectDenied(denials);
		}
	}

	/**
	 * Returns the strings of a JSON array.
	 *
	 * @param array the array
	 * @return the strings, in order
	 */
	private static List<String> strings(JsonNode array)
	{
		List<String> result = new ArrayList<>();
		for (JsonNode entry : array)
			result.add(entry.asString());
		return result;
	}

	/**
	 * A host project that holds an optimizer owner session, which holds the evidence of the session under test and of
	 * another session, and the nested session under test.
	 */
	private static final class Fixture
	{
		private final Path root;
		private final Path hostProject;
		private final Path owner;
		private final List<Path> evidence = new ArrayList<>();
		private final List<Path> evidenceFiles = new ArrayList<>();
		private final List<Path> deniedAround = new ArrayList<>();
		private final List<Path> deniedRecords = new ArrayList<>();
		private final NestedSessionManager manager;
		private final NestedSession nestedSession;
		private final SessionRequestAccess requestAccess;
		private final Path linkTargets;
		private final ChildPolicy bound;

		/**
		 * Creates the host project and the nested session.
		 *
		 * @param scope           the scope
		 * @param cleanup         removes the fixture's directories
		 * @param temporary       the directory that holds the fixture's directories
		 * @param mayRequest      {@code true} if the nested session may request sessions, and its project has a link to
		 *                        a read-only copy
		 * @throws IOException if a fixture cannot be created
		 */
		Fixture(HarnessRunnerScope scope, TemporaryDirectories cleanup, Path temporary, boolean mayRequest)
			throws IOException
		{
			root = cleanup.add(Files.createTempDirectory(temporary, "cat-evidence-read-")).toRealPath();
			hostProject = Files.createDirectories(root.resolve("project"));
			owner = Files.createDirectories(hostProject.resolve(".cat/sessions/owner"));
			Path response = Files.createDirectories(owner.resolve("responses/judge-response"));
			for (String name : List.of("0", "1"))
			{
				Path directory = Files.createDirectories(response.resolve("inputs/read_only_evidence").resolve(name));
				evidence.add(directory);
				evidenceFiles.add(Files.writeString(directory.resolve("transcript.jsonl"), "{}"));
			}
			deniedAround.add(Files.createDirectories(response.resolve("inputs/project")));
			deniedAround.add(Files.writeString(response.resolve("inputs/request.json"), "{}"));
			deniedAround.add(Files.writeString(response.resolve("runner.stdout"), ""));
			Path sibling = Files.createDirectories(owner.resolve("responses/sibling-response"));
			deniedAround.add(Files.writeString(Files.createDirectories(sibling.resolve("inputs/read_only_evidence/0")).
				resolve("transcript.jsonl"), "{}"));
			deniedAround.add(Files.writeString(Files.createDirectories(owner.resolve("requests")).resolve("request.json"),
				"{}"));
			deniedAround.add(Files.writeString(Files.createDirectories(owner.resolve("optimizer/held_out")).
				resolve("expected.json"), "{}"));
			deniedAround.add(Files.writeString(hostProject.resolve("README.md"), "Read me"));
			deniedAround.add(Files.writeString(Files.createDirectories(hostProject.resolve("client")).resolve("pom.xml"),
				"<project/>"));
			deniedAround.add(Files.writeString(Files.createDirectories(hostProject.resolve(".cat/sessions/other")).
				resolve("session.json"), "{}"));

			manager = new NestedSessionManager(scope.fileLocks(), owner.resolve("slots/h4"), Clock.systemUTC(),
				Yielding.fileReplacer());
			nestedSession = manager.create("claude");
			manager.snapshotProject(nestedSession);
			Path directory = nestedSession.directory();
			Files.createDirectories(directory.resolve("home/.claude"));
			Files.createDirectories(directory.resolve("claude"));
			Files.createDirectories(directory.resolve("project"));
			deniedRecords.addAll(List.of(directory.resolve("artifacts"), directory.resolve("session.json"),
				directory.resolve("initial-project")));
			if (mayRequest)
			{
				requestAccess = SessionRequestAccess.allowing(nestedSession, root.resolve("artifact"), 1,
					Platform.LINUX_X64, SessionRequestAccess.Shell.POSIX).orElseThrow();
				linkTargets = NestedSessionManager.linkTargets(directory);
				Files.writeString(Files.createDirectories(linkTargets).resolve("copy.txt"), "copy");
				Files.writeString(requestAccess.responses().resolve("limits.json"), "{}");
			}
			else
			{
				requestAccess = null;
				linkTargets = null;
			}
			// The request server bounds a nested session by a policy that carries the launch's denial of the host project
			bound = new ChildPolicy(List.of(owner.resolve("slots/h4")), List.of(), List.of(hostProject),
				List.of(), Set.of("api.anthropic.com"), false);
		}

		/**
		 * Writes the settings of the session under test and returns the rules that deny its file tools a read.
		 *
		 * @param scope     the scope
		 * @param sandboxed {@code true} if the session runs in a sandbox
		 * @param tools     the tools that the session's model is offered, or none for every tool
		 * @return the rules of {@code permissions.deny} that name a path
		 * @throws IOException if settings I/O fails
		 */
		List<String> denials(HarnessRunnerScope scope, boolean sandboxed, List<String> tools) throws IOException
		{
			Path destination = root.resolve("settings.json");
			// The runner passes the paths that the session must not read, which leave its evidence and nested session out
			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, nestedSession, evidence,
				List.of(hostProject), Map.of(), requestAccess);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				nestedSession.directory().resolve("claude"), nestedSession.directory().resolve("home"), List.of(),
				nestedSession.directory(), deniedReads, evidence, "", requestAccess,
				nestedSession.directory().resolve("project"), nestedSession.directory(), bound, ExtraPermissions.NONE,
				"api.anthropic.com", "", sandboxed, tools);
			return strings(scope.json().readTree(Files.readString(destination)).path("permissions").path("deny"));
		}

		/**
		 * Requires that no rule denies reading the read-only evidence, a directory or a file in it.
		 *
		 * @param denials the rules that deny a read
		 */
		void requireEvidenceReadable(List<String> denials)
		{
			for (Path path : evidence)
				requireThat(denying(denials, path), "denyingRule(" + path + ")").isEmpty();
			for (Path path : evidenceFiles)
				requireThat(denying(denials, path), "denyingRule(" + path + ")").isEmpty();
		}

		/**
		 * Requires that no rule denies reading what the nested session's own settings let it read beyond its project and
		 * home: its responses directory and the read-only copies that its project's links lead to.
		 *
		 * @param denials the rules that deny a read
		 */
		void requireNestedSessionReadable(List<String> denials)
		{
			Path responses = requestAccess.responses();
			for (Path path : List.of(responses, responses.resolve("limits.json"), linkTargets,
				linkTargets.resolve("copy.txt")))
			{
				requireThat(denying(denials, path), "denyingRule(" + path + ")").isEmpty();
			}
		}

		/**
		 * Requires that rules deny reading the rest of the host project that the evidence lies in: the files around the
		 * evidence, the other responses beside it, the owner session's other records and the runner's records of the
		 * nested session.
		 *
		 * @param denials the rules that deny a read
		 */
		void requireRestOfHostProjectDenied(List<String> denials)
		{
			List<Path> denied = new ArrayList<>(deniedAround);
			denied.addAll(deniedRecords);
			for (Path path : denied)
				requireThat(denying(denials, path), "denyingRule(" + path + ")").isNotEmpty();
		}

		/**
		 * Returns the rules that deny reading a path: a rule for the path itself, or for a directory that holds it.
		 *
		 * @param denials the rules that deny a read
		 * @param path    the path
		 * @return the rules, which are empty if none denies the path
		 */
		private static List<String> denying(List<String> denials, Path path)
		{
			List<String> result = new ArrayList<>();
			for (Path candidate = path; candidate != null; candidate = candidate.getParent())
			{
				String rule = "Read(/" + ClaudeConnectionSettings.rulePath(candidate);
				if (denials.contains(rule + "/**)") || candidate.equals(path) && denials.contains(rule + ")"))
					result.add(rule);
			}
			return result;
		}
	}
}
