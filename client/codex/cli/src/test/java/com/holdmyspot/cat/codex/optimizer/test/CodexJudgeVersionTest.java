package com.holdmyspot.cat.codex.optimizer.test;


import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexProfileInventory;
import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection;
import com.holdmyspot.cat.common.optimizer.port.contract.test.JudgeVersionContent;
import com.holdmyspot.cat.common.optimizer.port.contract.test.VersionRegistry;
import com.holdmyspot.cat.common.optimizer.proposal.JudgeTemplate;
import com.holdmyspot.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the judge version that {@link CodexSessions} declares.
 */
public final class CodexJudgeVersionTest
{
	/**
	 * The registry of judge versions and the hash of the judge profile and rendered evidence section of each.
	 */
	private static final Path REGISTRY = Path.of("src/test/resources/optimizer/judge-versions.txt");
	/**
	 * The plugin resources that hold the role profile's agent definition.
	 */
	private static final Path ARTIFACT = Path.of("../plugin/src/main/resources");
	/**
	 * The recorded harness events whose evidence section the hash covers.
	 */
	private static final Path EVENTS = Path.of("src/test/resources/optimizer/codex-tool-calls.jsonl");
	/**
	 * The recorded run artifacts of a trial whose root started two children and received their findings, whose
	 * rendered activity the hash also covers.
	 */
	private static final Path CHILDREN = Path.of("src/test/resources/optimizer/two-children/transcript.jsonl");
	/**
	 * The recorded run artifacts of a trial whose child received a result from a command that was still running,
	 * whose rendered activity the hash also covers.
	 */
	private static final Path RUNNING = Path.of("src/test/resources/optimizer/running-command/transcript.jsonl");

	/**
	 * Creates the test.
	 */
	public CodexJudgeVersionTest()
	{
	}

	/**
	 * Verifies that the declared judge version is registered with the hash of the judge profile, built from the
	 * packaged role profile with a fixed rubric and decision rule, and of the evidence section rendered for recorded
	 * harness events and the activity rendered for a recorded trial with two children and for a recorded trial whose
	 * child received a result from a command that was still running, so that changing any of them
	 * requires a decision about the version.
	 *
	 * @throws IOException if the fixtures, the role profile or the registry cannot be read
	 */
	@Test
	public void versionMatchesJudge() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-M1
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory("cat-codex-judge-version-"));
				Path source = Files.createDirectories(root.resolve("source"));
				Path effects = Files.createDirectories(root.resolve("effects"));
				Files.writeString(source.resolve("README.md"), "Before\n", UTF_8);
				Files.writeString(effects.resolve("README.md"), "After\n", UTF_8);
				for (Path file : List.of(source.resolve("README.md"), effects.resolve("README.md")))
					PortableFiles.setMode(file, PosixFilePermissions.fromString("rw-r--r--"));

				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root, List::of, "", false, Map.of(),
					root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				CodexSessions sessions = new CodexSessions(settings);
				Path transcript = RecordedTrial.write(root, EVENTS, List.of(RecordedTrial.commandResult(1,
					"error building bubblewrap command: app-server socket directory must be a user-owned directory with " +
						"mode 0700\n")));
				// Run directories differ per run, so the hash covers the section with their root replaced. The paths that
				// the section names are written with "/" so that every platform hashes the same content.
				String section = EvidenceSection.render(sessions.readEvidence(transcript), transcript, effects, source).
					replace(transcript.toString(), "<root>/" + PortablePaths.text(root.relativize(transcript))).
					replace(effects.toString(), "<root>/effects").
					replace(root.toString(), "<root>");
				CodexProfileInventory inventory = new CodexProfileInventory(ARTIFACT.toAbsolutePath().normalize(), "codex",
					_ ->
					{
						throw new IOException("Reading the role profile does not run Codex");
					});
				JudgeProfile judge = new JudgeTemplate(JsonMapper.builder().build(), inventory.roleProfile()).
					profile("The reply is brief.", "Pass only if every rubric item holds.");
				String children = sessions.readEvidence(CHILDREN).render() + sessions.readEvidence(RUNNING).render();
				VersionRegistry.requireRegistered(REGISTRY, "judge", sessions.judgeVersion(),
					VersionRegistry.hash(JudgeVersionContent.of(judge, section + children)));
			}
		}
	}
}
