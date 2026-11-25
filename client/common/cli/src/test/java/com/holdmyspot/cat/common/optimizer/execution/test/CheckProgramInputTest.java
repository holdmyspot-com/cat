package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.cli.EvaluatorMetadata;
import com.holdmyspot.cat.common.optimizer.cli.EvaluatorRuntime;
import com.holdmyspot.cat.common.optimizer.cli.PeerTrialSessions;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.evidence.CommandSummary;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramInput;
import com.holdmyspot.cat.common.optimizer.execution.TrialEvidence;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.test.FakeProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.RetainedNestedSessionClient;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder.Route;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests the files supplied to a check program from a completed trial.
 */
public final class CheckProgramInputTest
{
	/**
	 * Creates the test.
	 */
	public CheckProgramInputTest()
	{
	}

	/**
	 * Provides trials completed by the local port or an installed peer's port.
	 *
	 * @return the evaluator, its tool name and whether it is the peer
	 */
	@DataProvider
	public static Object[][] evaluators()
	{
		return new Object[][]{{Harness.CODEX, "exec_command", false}, {Harness.CLAUDE, "Bash", true}};
	}

	/**
	 * The local and peer trial records produce the same check-program layout, including both project snapshots,
	 * the ordered version-one activity and every reply. Only the documented inputs reach that layout.
	 *
	 * @param harness the evaluator
	 * @param tool    its native tool name
	 * @param peer    whether the activity comes from the peer sidecar
	 * @throws IOException if a fixture cannot be written or the inputs cannot be staged
	 */
	@Test(dataProvider = "evaluators")
	public void stagesCompletedTrialEvidence(Harness harness, String tool, boolean peer) throws IOException
	{
		// Requirement: R-L21
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "check-program-input"));
			Path retained = Files.createDirectory(root.resolve("retained"));
			Path initial = Files.createDirectory(retained.resolve("initial-project"));
			Path effects = Files.createDirectory(retained.resolve("project"));
			Path artifacts = Files.createDirectory(retained.resolve("artifacts"));
			Path transcript = Files.writeString(artifacts.resolve("transcript.jsonl"), "{\"native\":true}\n");
			FileContent before = new FileContent(new byte[]{0, (byte) 0xff, 1});
			FileContent after = new FileContent(new byte[]{0, (byte) 0xff, 2});
			Files.createDirectory(initial.resolve("data"));
			Files.createDirectory(effects.resolve("data"));
			Files.write(initial.resolve("data/state.bin"), before.bytes());
			Files.write(effects.resolve("data/state.bin"), after.bytes());
			List<List<String>> replies = List.of(List.of("interim", "first final"), List.of("second final"));
			SessionResult.Completed completed = new SessionResult.Completed(transcript, replies, effects, initial,
				"fixture-model", null, null, List.of(), "none", List.of());

			SessionPort sessions;
			if (peer)
			{
				Files.writeString(artifacts.resolve("evaluator-evidence.json"), activityJson(tool));
				sessions = peerPort(scope, root, harness);
			}
			else
			{
				FakeSessionPort local = new FakeSessionPort();
				local.supplyEvidence(transcript, activity(tool));
				sessions = local;
			}
			TrialRecord trial = TrialEvidence.read(sessions, completed);
			Path input = Files.createDirectory(root.resolve("input"));
			FileContent check = new FileContent("class Check {}\n".getBytes(UTF_8));
			FileContent helper = new FileContent("class Helper {}\n".getBytes(UTF_8));
			JsonMapper json = scope.json();

			CheckProgramInput.stage(json, input, trial, Map.of("Check.java", check, "support/Helper.java", helper));

			try (Stream<Path> paths = Files.walk(input))
			{
				Set<Path> staged = paths.filter(path -> !path.equals(input)).map(input::relativize).
					collect(Collectors.toSet());
				requireThat(staged, "staged").isEqualTo(Set.of(Path.of("project"), Path.of("project/data"),
					Path.of("project/data/state.bin"), Path.of("initial"), Path.of("initial/data"),
					Path.of("initial/data/state.bin"), Path.of("activity.json"), Path.of("replies.json"),
					Path.of("checks"), Path.of("checks/Check.java"), Path.of("checks/support"),
					Path.of("checks/support/Helper.java")));
			}
			requireThat(new FileContent(Files.readAllBytes(input.resolve("project/data/state.bin"))), "finalProject").
				isEqualTo(after);
			requireThat(new FileContent(Files.readAllBytes(input.resolve("initial/data/state.bin"))), "initialProject").
				isEqualTo(before);
			requireThat(new FileContent(Files.readAllBytes(input.resolve("checks/Check.java"))), "check").
				isEqualTo(check);
			requireThat(new FileContent(Files.readAllBytes(input.resolve("checks/support/Helper.java"))), "helper").
				isEqualTo(helper);
			requireThat(json.readTree(Files.readString(input.resolve("activity.json"))), "activity").
				isEqualTo(json.readTree(activityJson(tool)));
			requireThat(json.readTree(Files.readString(input.resolve("replies.json"))), "replies").
				isEqualTo(json.readTree("""
					[["interim", "first final"], ["second final"]]
					"""));
		}
	}

	/**
	 * Returns the normalized activity the local port has read.
	 *
	 * @param tool the evaluator's native tool name
	 * @return the trial activity
	 */
	private static SessionEvidence activity(String tool)
	{
		return new SessionEvidence(List.of(
			new SessionEvidence.Tool(1, "root", tool, "printf output", true, 0, "full output with tail"),
			new SessionEvidence.ChildEvent(1, "root", "child-1", "",
				SessionEvidence.ChildEvent.Kind.STARTED, "Check a file"),
			new SessionEvidence.Tool(2, "child-1", "Read", "data/state.bin", false, null, "child output")),
			List.of("full output", "child output"), new CommandSummary(Duration.ofMillis(125), 1));
	}

	/**
	 * Returns literal version-one activity for the peer and the expected check-program input.
	 *
	 * @param tool the evaluator's native tool name
	 * @return the activity file contents
	 */
	private static String activityJson(String tool)
	{
		return """
			{
			  "format_version": 1,
			  "entries": [
			    {"type": "tool", "entry": {"turn": 1, "agent": "root", "tool": "%s",
			      "input": "printf output", "command": true, "exit_status": 0,
			      "output": "full output with tail", "running": []}},
			    {"type": "child_event", "entry": {"turn": 1, "agent": "root", "child": "child-1", "name": "",
			      "kind": "STARTED", "text": "Check a file"}},
			    {"type": "tool", "entry": {"turn": 2, "agent": "child-1", "tool": "Read", "input": "data/state.bin",
			      "command": false, "exit_status": null, "output": "child output", "running": []}}
			  ],
			  "received": ["full output", "child output"],
			  "slowest_command": "PT0.125S",
			  "empty_running_results": 1
			}
			""".formatted(tool);
	}

	/**
	 * Returns a peer port for reading fixture evidence through the production sidecar reader.
	 *
	 * @param scope   the test's process dependencies
	 * @param root    the test's temporary directory
	 * @param harness the peer evaluator
	 * @return the peer port
	 */
	private static PeerTrialSessions peerPort(TestJvmScope scope, Path root, Harness harness)
	{
		FakeProfileInventory profiles = new FakeProfileInventory();
		EvaluatorMetadata metadata = new EvaluatorMetadata(harness,
			new ProfileEvidence(profiles.inventory(), profiles.ranking(), Map.of()), profiles.roleProfile(),
			List.of(), "0".repeat(64), List.of(),
			new EvaluatorRuntime("test-1.0", 1, "1.0", 1, true, HostLogin.UNKNOWN), Optional.empty());
		RetainedNestedSessionClient retainedTrials = new RetainedNestedSessionClient(Optional::empty, scope.json(),
			Thread::yield);
		return new PeerTrialSessions(scope, root.resolve("installed-peer"), metadata, Route.SERVER,
			root.resolve("requests"), root, root.resolve("results"), List::of, HarnessCapacity.unlimited(),
			retainedTrials);
	}
}
