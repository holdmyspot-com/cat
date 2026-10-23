package com.holdmyspot.cat.claude.optimizer.test;


import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.ClaudeProfileInventory;
import com.holdmyspot.cat.claude.optimizer.ClaudeSessions;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.evidence.EvidenceSection;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
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
 * Guards the judge version that {@link ClaudeSessions} declares.
 */
public final class ClaudeJudgeVersionTest
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
	private static final Path EVENTS = Path.of("src/test/resources/optimizer/claude-tool-calls.jsonl");

	/**
	 * Creates the test.
	 */
	public ClaudeJudgeVersionTest()
	{
	}

	/**
	 * Verifies that the declared judge version is registered with the hash of the judge profile, built from the
	 * packaged role profile with a fixed rubric and decision rule, and of the evidence section rendered for recorded
	 * harness events, so that changing either requires a decision about the version.
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
				Path root = cleanup.add(Files.createTempDirectory("cat-claude-judge-version-"));
				Path source = Files.createDirectories(root.resolve("source"));
				Path effects = Files.createDirectories(root.resolve("effects"));
				Files.writeString(source.resolve("README.md"), "Before\n", UTF_8);
				Files.writeString(effects.resolve("README.md"), "After\n", UTF_8);
				for (Path file : List.of(source.resolve("README.md"), effects.resolve("README.md")))
					PortableFiles.setMode(file, PosixFilePermissions.fromString("rw-r--r--"));

				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root, List::of, "", false, Map.of(),
					root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				ClaudeSessions sessions = new ClaudeSessions(settings);
				// Run directories differ per run, so the hash covers the section with their root replaced. The paths that
				// the section names are written with "/" so that every platform hashes the same content.
				String section = EvidenceSection.render(sessions.readEvidence(EVENTS), EVENTS, effects, source).
					replace(EVENTS.toString(), PortablePaths.text(EVENTS)).
					replace(effects.toString(), "<root>/effects").
					replace(root.toString(), "<root>");
				TrialProfile role = new ClaudeProfileInventory(ARTIFACT.toAbsolutePath().normalize()).roleProfile();
				JudgeProfile judge = new JudgeTemplate(JsonMapper.builder().build(), role).profile("The reply is brief.",
					"Pass only if every rubric item holds.");
				VersionRegistry.requireRegistered(REGISTRY, "judge", sessions.judgeVersion(),
					VersionRegistry.hash(JudgeVersionContent.of(judge, section)));
			}
		}
	}
}
