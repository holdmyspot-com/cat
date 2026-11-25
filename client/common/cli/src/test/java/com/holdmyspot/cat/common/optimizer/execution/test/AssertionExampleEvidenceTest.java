package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionExamples;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.RecordedTrial;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.evidence.ActivityFile;
import com.holdmyspot.cat.common.optimizer.evidence.EvidencePrompt;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.execution.AssertionExampleEvidence;
import com.holdmyspot.cat.common.optimizer.execution.prompt.JudgePrompt;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.JudgeTemplate;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the judge requests built from an assertion example.
 */
public final class AssertionExampleEvidenceTest
{
	private static final TestCase CASE = new TestCase(Map.of("label", new TestValue.Text("River")),
		"Arrives River", "same_day");
	private static final DrawnTestCase DRAWN = new DrawnTestCase("arrival", "arrival", CASE,
		List.of("Find River.", "Check the location.", "Confirm the answer."), "Report the location.", "written",
		DrawStream.Kind.DEVELOPMENT, 0, true);
	private static final List<List<String>> REPLIES = List.of(List.of("Checking River.", "River found."),
		List.of("Location confirmed."), List.of());
	private final JsonMapper json = JsonMapper.builder().build();
	private final JudgeProfile profile = new JudgeTemplate(json, new TrialProfile("judge-model", "high")).
		profile("Report the location accurately.", "Pass only when the evidence supports the assertion.");

	/**
	 * Creates the test.
	 */
	public AssertionExampleEvidenceTest()
	{
	}

	/**
	 * Checks that the actions judge gets the example's replies, normalized activity and project changes.
	 *
	 * @throws IOException if the example cannot be prepared or read
	 */
	@Test
	public void actionsOffersActivityAndProject() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("example-evidence"));
			Path rendered = Files.createDirectory(root.resolve("rendered"));
			Path scratch = Files.createDirectory(root.resolve("judge-evidence"));
			RecordedTrial example = example(root, rendered);
			SessionRequest request = request(root, scratch, example, EvidenceScope.ACTIONS);
			EvidencePrompt prompt = request.evidencePrompt().orElseThrow();
			Path activity = prompt.transcript();
			requireThat(activity, "activity").isRegularFile();
			requireThat(activity.startsWith(scratch), "ownedActivity").isTrue();

			Path project = rendered.resolve("project");
			requireThat(request.readOnly(), "readOnly").isEqualTo(List.of(project, activity.getParent()));
			requireThat(Files.readString(project.resolve("River.txt")), "projectContent").isEqualTo("New location River");
			SessionEvidence normalized = ActivityFile.read(json, activity, UnaryOperator.identity());
			requireThat(normalized, "normalized").isEqualTo(example.activity());
			requireThat(normalized.received(), "received").isEqualTo(List.of("coordinates River"));
			requireThat(normalized.entries().getFirst(), "toolEntry").isEqualTo(
				new SessionEvidence.Tool(1, "root", "Read", "River.txt", false, null, "coordinates River", List.of()));
			requireThat(normalized.commands().emptyRunningResults(), "emptyRunningResults").isEqualTo(2);

			String turn = request.turns().getFirst();
			requireThat(turn, "turn").contains("one listed assertion").contains("normalized activity").
				contains("File changes").contains("Old location River").contains("New location River").
				contains("Find River.").
				contains("The evidence supports the River location and this outcome. " + CASE.expectedOutcome());
			requireThat(json.readTree(prompt.values().get("responses")), "responses").isEqualTo(json.valueToTree(REPLIES));
			requireThat(json.readTree(prompt.values().get("assertions")).size(), "assertionCount").isEqualTo(1);
			requireThat(request.profile(), "profile").isEqualTo(profile.profile());
			requireThat(request.role(), "role").isEqualTo(SessionRole.JUDGE);
			requireThat(request.part(), "part").isEqualTo(Part.HELD_OUT);

			Path offered = Path.of("offered");
			String staged = prompt.render(path -> offered.resolve(path.getFileName()));
			requireThat(staged, "staged").contains("Transcript: " + offered.resolve(activity.getFileName())).
				contains("Effects directory: " + offered.resolve(project.getFileName())).
				doesNotContain(activity.toString()).doesNotContain(project.toString());
		}
	}

	/**
	 * Checks that a response judge gets the filled statement and replies, with no evidence directory or file.
	 *
	 * @throws IOException if the example cannot be prepared or read
	 */
	@Test
	public void responseOffersOnlyCaseAndReplies() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("example-evidence"));
			Path rendered = Files.createDirectory(root.resolve("rendered"));
			Path scratch = Files.createDirectory(root.resolve("judge-evidence"));
			RecordedTrial example = example(root, rendered);
			SessionRequest request = request(root, scratch, example, EvidenceScope.RESPONSE);
			requireThat(request.readOnly(), "readOnly").isEmpty();
			requireThat(request.evidencePrompt().isEmpty(), "evidencePrompt.isEmpty()").isTrue();
			String turn = request.turns().getFirst();
			requireThat(turn, "turn").contains("Find River.").
				contains("The evidence supports the River location and this outcome. " + CASE.expectedOutcome()).
				doesNotContain("coordinates River").
				doesNotContain("New location River").doesNotContain("File changes").
				doesNotContain(root.toString());
			int start = turn.indexOf("\n\n[", turn.indexOf("The trial agent's reply"));
			int end = turn.indexOf("\n\n### How to judge", start);
			requireThat(json.readTree(turn.substring(start, end)), "responses").isEqualTo(json.valueToTree(REPLIES));
			try (Stream<Path> children = Files.list(scratch))
			{
				requireThat(children.count(), "scratchEntries").isEqualTo(0L);
			}
		}
	}

	/**
	 * Renders a multi-turn example with a file change and one tool result.
	 *
	 * @param root the directory for the authored fixture
	 * @param rendered the directory for the rendered files
	 * @return the rendered example
	 * @throws IOException if the fixture cannot be prepared or read
	 */
	private RecordedTrial example(Path root, Path rendered) throws IOException
	{
		Path replies = Files.writeString(root.resolve("replies.json"), """
			[["Checking {{label}}.", "{{label}} found."], ["Location confirmed."]]
			""");
		Path activity = Files.writeString(root.resolve("activity.json"), """
			{
			  "format_version": 1,
			  "entries": [{"type":"tool","entry":{
			    "turn":1,"agent":"root","tool":"Read","input":"{{label}}.txt","command":false,
			    "exit_status":null,"output":"coordinates {{label}}","running":[]
			  }}],
			  "received":["coordinates {{label}}"],
			  "empty_running_results":2
			}
			""");
		Path before = Files.writeString(root.resolve("before.txt"), "Old location {{label}}");
		Path after = Files.writeString(root.resolve("after.txt"), "New location {{label}}");
		return AssertionExamples.render(json, DRAWN.turns().size(), CASE, Map.of("replies.json", replies,
			"activity.json", activity, "initial/{{label}}.txt", before, "project/{{label}}.txt", after),
			Set.of("initial", "project"), null, rendered);
	}

	/**
	 * Builds the request from the recorded replies and snapshot paths.
	 *
	 * @param root the directory for the fixture
	 * @param scratch the evidence directory
	 * @param example the rendered record
	 * @param scope the assertion's evidence scope
	 * @return the judge request
	 * @throws IOException if the request's evidence cannot be prepared
	 */
	private SessionRequest request(Path root, Path scratch, RecordedTrial example, EvidenceScope scope)
		throws IOException
	{
		Assertion assertion = new Assertion("supports_location", AssertionKind.EFFECT, null, OnToolFailure.FAIL,
			"The evidence supports the {{label}} location and this outcome. {{expected_outcome}}",
			"Judge the meaning of the evidence.", scope);
		Requirement requirement = new Requirement("report_location", "Report the location.", "prompt.md", null,
			List.of("incorrect_location"));
		Path judgeDirectory = Files.createDirectory(root.resolve("judge"));
		AssertionExampleEvidence evidence = new AssertionExampleEvidence(json, new JudgePrompt(json, judgeDirectory));
		return evidence.request(profile, DRAWN, example, new JudgedAssertion(assertion, requirement), scratch);
	}
}
