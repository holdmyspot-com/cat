package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.HunkEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorConflict;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorManifest;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.PromptAuthorPrompt;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;
import java.nio.charset.StandardCharsets;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorApproach;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorBrief;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.secrets.PromptTemplateSecrets;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import java.nio.file.Path;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the prompt author's evidence manifest and editing instructions.
 */
public final class PromptAuthorManifestTest
{
	/**
	 * Creates the test.
	 */
	public PromptAuthorManifestTest()
	{
	}

	/**
	 * Verifies that a complete prompt author report can be read even when no edit is required.
	 */
	@Test
	public void readsCompleteManifest()
	{
		// Requirement: R-D10
		JsonMapper json = JsonMapper.builder().build();
		PromptAuthorPrompt prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(Path.of("CLAUDE.md")),
			Set.of());
		String response = """
			{"needed_edits": [], "kinds": [], "hunks": [], "conflicts": []}
			""";
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript").toAbsolutePath(),
			List.of(List.of(response)), Path.of("/effects").toAbsolutePath(), Path.of("/initial-project").toAbsolutePath(),
			"model", null, null, List.of(), "none", List.of());
		requireThat(prompt.neededEdits(result).isPresent(), "accepted").isTrue();
	}

	/**
	 * Verifies that a conflict report is read with its ordered resolutions and exact quotation.
	 */
	@Test
	public void readsConflictReport()
	{
		// Requirement: R-D13
		JsonMapper json = JsonMapper.builder().build();
		PromptAuthorPrompt prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of());
		String response = """
			{"needed_edits":[],"kinds":[{"name":"contradictory permission","failures":["f"],"classification":"conflict",
			 "hunks":[]}],"hunks":[],
			 "conflicts":[{"failures":["f"],"requirement_ids":["r1","r2"],
			 "quotations":[{"path":"CLAUDE.md","text":"Ask first."}],
			 "resolutions":["Keep asking","Allow deletion"],"recommended":1,"reason":"Keeps permission."}]}
			""";
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript").toAbsolutePath(),
			List.of(List.of(response)), Path.of("/effects").toAbsolutePath(), Path.of("/initial-project").toAbsolutePath(),
			"model", null, null, List.of(), "none", List.of());
		requireThat(prompt.report(result).isPresent(), "accepted").isTrue();
		PromptAuthorConflict conflict = prompt.report(result).orElseThrow().conflicts().getFirst();
		requireThat(conflict.resolutions(), "resolutions").isEqualTo(List.of("Keep asking", "Allow deletion"));
		requireThat(conflict.quotations().getFirst().text(), "quotation").isEqualTo("Ask first.");
		requireThat(conflict.recommended(), "recommended").isEqualTo(1);
	}

	/**
	 * Returns malformed conflict fields that the report reader must reject.
	 *
	 * @return property names and invalid JSON values
	 */
	@DataProvider
	public static Object[][] invalidConflictFields()
	{
		return new Object[][]
			{
				{"failures", "[]"}, {"requirement_ids", "[\"r1\"]"},
				{"requirement_ids", "[\"r1\",\"r1\"]"}, {"quotations", "[]"},
				{"quotations", "[{\"path\":\"../rules.md\",\"text\":\"Ask\"}]"},
				{"quotations", "[{\"path\":\"rules.md\",\"text\":\" \"}]"},
				{"resolutions", "[\"One\"]"}, {"resolutions", "[\"One\",\"One\"]"},
				{"resolutions", "[\"One\",\" \"]"}, {"recommended", "1.5"}, {"reason", "\" \""}
			};
	}

	/**
	 * Verifies that malformed conflicts cannot become domain reports.
	 *
	 * @param property the invalid field
	 * @param value its JSON value
	 */
	@Test(dataProvider = "invalidConflictFields")
	public void rejectsMalformedConflict(String property, String value)
	{
		// Requirement: R-D13
		JsonMapper json = JsonMapper.builder().build();
		PromptAuthorPrompt prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of());
		JsonNode report = json.readTree("""
			{"needed_edits":[],"kinds":[],"hunks":[],"conflicts":[
			 {"failures":["f"],"requirement_ids":["r1","r2"],"quotations":[{"path":"CLAUDE.md","text":"Ask"}],
			 "resolutions":["First","Second"],"recommended":1,"reason":"Reason"}]}
			""");
		((ObjectNode) report.get("conflicts").get(0)).set(property, json.readTree(value));
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript").toAbsolutePath(),
			List.of(List.of(json.writeValueAsString(report))), Path.of("/effects").toAbsolutePath(),
			Path.of("/initial-project").toAbsolutePath(), "model", null, null, List.of(), "none", List.of());
		requireThat(prompt.report(result), "malformed").isEmpty();
	}

	/**
	 * Verifies that only confirmed file content can support a reported contract conflict.
	 */
	@Test
	public void validatesConfirmedQuotation()
	{
		// Requirement: R-D13
		Path path = Path.of("CLAUDE.md");
		FileContent content = new FileContent("Ask first.".getBytes(StandardCharsets.UTF_8));
		FileInventory incumbent = new FileInventory(Path.of("/project").toAbsolutePath(),
			Map.of(path, new FileEntry.RegularFile(content.hash(), Set.of())));
		PromptAuthorConflict conflict = new PromptAuthorConflict(List.of("f"), List.of("r1", "r2"),
			List.of(new PromptAuthorConflict.Quotation(path, "Ask first.")), List.of("Keep asking", "Allow deletion"),
			1, "Keeps permission.");
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
			new PromptAuthorKind("contradictory permission", List.of("f"), FailureKind.CONFLICT, List.of(), "")), List.of());
		requireThat(manifest.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(path, content), List.of(conflict), Map.of()), "confirmed").isEmpty();
		FileContent edited = new FileContent("Delete immediately.".getBytes(StandardCharsets.UTF_8));
		PromptAuthorConflict introduced = new PromptAuthorConflict(List.of("f"), List.of("r1", "r2"),
			List.of(new PromptAuthorConflict.Quotation(path, "Delete immediately.")), conflict.resolutions(), 1,
			conflict.reason());
		requireThat(manifest.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(path, content), List.of(introduced), Map.of()).toString(), "introduced").contains("CLAUDE.md");
		requireThat(manifest.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(path, edited), List.of(introduced), Map.of()).toString(), "wrongSnapshot").contains("CLAUDE.md");
	}

	/**
	 * Verifies that classifications, contract ids and recommendation positions agree with conflict citations.
	 */
	@Test
	public void validatesConflictReferences()
	{
		// Requirement: R-D13
		FileInventory incumbent = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of());
		PromptAuthorConflict conflict = new PromptAuthorConflict(List.of("f"), List.of("r1", "unknown"),
			List.of(new PromptAuthorConflict.Quotation(Path.of("missing.md"), "Missing text")),
			List.of("First", "Second"), 3, "Reason");
		PromptAuthorManifest guidance = new PromptAuthorManifest(List.of(new PromptAuthorKind("deletion", List.of("f"),
			FailureKind.GUIDANCE, List.of(), "")), List.of());
		List<String> violations = guidance.violations(Set.of("f"), List.of(), ContractFixtures.contract(),
			incumbent, Map.of(), List.of(conflict), Map.of());
		requireThat(violations.toString(), "violations").contains("not classified conflict").contains("unknown").
			contains("recommended").contains("missing.md");
		PromptAuthorManifest classified = new PromptAuthorManifest(List.of(new PromptAuthorKind("deletion",
			List.of("f"), FailureKind.CONFLICT, List.of(), "")), List.of());
		requireThat(classified.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(), List.of(), Map.of()).toString(), "uncited").contains("no conflict");
	}

	/**
	 * Verifies that the prompt author receives all four evidence-based editing steps.
	 */
	// Requirement: R-D3
	@Test
	public void explainsClassificationAndGeneralization()
	{
		// Requirement: R-D10
		JsonMapper json = JsonMapper.builder().build();
		PromptAuthorPrompt prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(Path.of("CLAUDE.md")),
			Set.of());
		PromptAuthorBrief brief = new PromptAuthorBrief("safety", PromptAuthorApproach.CORRECT_INSTRUCTION,
			ContractFixtures.contract(), List.of(), Map.of(), Map.of(), List.of(),
			Map.of(), "", List.of(), List.of(), List.of());
		String turn = prompt.request(brief, Path.of("/copy").toAbsolutePath()).turns().getFirst();
		requireThat(turn, "instructions").contains("routing").contains("not_guidance").contains("smallest").
			contains("contradiction").contains("analogous").contains("dissimilar").contains("manifest");
	}
	/**
	 * Returns dissimilar cases and whether the confirmed contract has a test with that identifier.
	 *
	 * @return the dissimilar case and whether the manifest is accepted
	 */
	@DataProvider
	public static Object[][] dissimilarCases()
	{
		return new Object[][]
			{
				{"plain_list", true},
				{"A question that needs no file", false}
			};
	}

	/**
	 * Verifies that a hunk's dissimilar case must be the identifier of a test of the confirmed contract, and that the
	 * rejection names the hunk and the value.
	 *
	 * @param dissimilar the hunk's dissimilar case
	 * @param accepted whether the manifest is accepted
	 */
	@Test(dataProvider = "dissimilarCases")
	public void dissimilarCaseIsAContractTest(String dissimilar, boolean accepted)
	{
		// Requirement: R-D17
		Path path = Path.of("rules/safety.md");
		Hunk hunk = new Hunk(path, 1, 2, 3, 2, 4);
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(new PromptAuthorKind("deletion", List.of("f"),
			FailureKind.GUIDANCE, List.of("h1"), "")), List.of(new HunkEvidence("h1", path, 2, 3, 2, 4, List.of("f"),
			"Another deletion request", dissimilar)));

		List<String> violations = manifest.violations(Set.of("f"), List.of(hunk), ContractFixtures.contract(),
			new FileInventory(Path.of("/project").toAbsolutePath(), Map.of()), Map.of(), List.of(), Map.of());

		if (accepted)
			requireThat(violations, "violations").isEmpty();
		else
		{
			requireThat(violations.size(), "violations.size()").isEqualTo(1);
			requireThat(violations.getFirst(), "violation").contains("rules/safety.md").contains("2..4").
				contains("'" + dissimilar + "'");
		}
	}

	/**
	 * Verifies that a manifest whose kinds leave out a received failure is rejected, and that the rejection names the
	 * failure.
	 */
	@Test
	public void rejectsFailureOutsideEveryKind()
	{
		// Requirement: R-D10
		Path path = Path.of("rules/safety.md");
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
				new PromptAuthorKind("mistyped path", List.of("f"), FailureKind.GUIDANCE, List.of("h1"), "")),
			List.of(new HunkEvidence("h1", path, 2, 3, 2, 4, List.of("f"), "Another read", "plain_list")));

		List<String> violations = violations(manifest, Set.of("f", "g"), List.of(new Hunk(path, 1, 2, 3, 2, 4)));

		requireThat(violations, "violations").isEqualTo(List.of("Failure g is outside every kind"));
	}

	/**
	 * Verifies that a manifest that puts one received failure into two kinds is rejected, because a failure's trial
	 * first departs from the contract at one action.
	 */
	@Test
	public void rejectsFailureInTwoKinds()
	{
		// Requirement: R-D10
		Path path = Path.of("rules/safety.md");
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
				new PromptAuthorKind("mistyped path", List.of("f"), FailureKind.GUIDANCE, List.of("h1"), ""),
				new PromptAuthorKind("skipped part", List.of("f"), FailureKind.ROUTING, List.of("h1"), "")),
			List.of(new HunkEvidence("h1", path, 2, 3, 2, 4, List.of("f"), "Another read", "plain_list")));

		List<String> violations = violations(manifest, Set.of("f"), List.of(new Hunk(path, 1, 2, 3, 2, 4)));

		requireThat(violations, "violations").isEqualTo(List.of("Failure f is in more than one kind"));
	}

	/**
	 * Verifies that a guidance kind that names no hunk is rejected, even when the attempt edits nothing.
	 */
	@Test
	public void rejectsGuidanceKindWithoutHunk()
	{
		// Requirement: R-D10
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
			new PromptAuthorKind("mistyped path", List.of("f"), FailureKind.GUIDANCE, List.of(), "")), List.of());

		List<String> violations = violations(manifest, Set.of("f"), List.of());

		requireThat(violations, "violations").isEqualTo(List.of("Kind 'mistyped path' is classified guidance but " +
			"names no hunk that addresses it"));
	}

	/**
	 * Verifies that a kind that names a hunk absent from the manifest's hunks is rejected.
	 */
	@Test
	public void rejectsKindNamingUnknownHunk()
	{
		// Requirement: R-D10
		Path path = Path.of("rules/safety.md");
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
				new PromptAuthorKind("mistyped path", List.of("f"), FailureKind.ROUTING, List.of("h1", "h2"), "")),
			List.of(new HunkEvidence("h1", path, 2, 3, 2, 4, List.of("f"), "Another read", "plain_list")));

		List<String> violations = violations(manifest, Set.of("f"), List.of(new Hunk(path, 1, 2, 3, 2, 4)));

		requireThat(violations, "violations").isEqualTo(List.of("Kind 'mistyped path' names an unknown hunk: h2"));
	}

	/**
	 * Verifies that a manifest that groups every received failure into a kind, addresses each guidance kind with a
	 * hunk and cites every changed hunk is accepted and offers a candidate.
	 */
	@Test
	public void acceptsCompleteManifest()
	{
		// Requirement: R-D10
		Path path = Path.of("rules/safety.md");
		Hunk changed = new Hunk(path, 1, 2, 3, 2, 4);
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
				new PromptAuthorKind("mistyped path", List.of("f", "g"), FailureKind.GUIDANCE, List.of("h1"), ""),
				new PromptAuthorKind("judge outage", List.of("h"), FailureKind.NOT_GUIDANCE, List.of(), "")),
			List.of(new HunkEvidence("h1", path, 2, 3, 2, 4, List.of("f", "g"), "Another read", "plain_list")));

		requireThat(violations(manifest, Set.of("f", "g", "h"), List.of(changed)), "violations").isEmpty();
		requireThat(manifest.producesCandidate(List.of(changed)), "producesCandidate").isTrue();
	}

	/**
	 * Verifies that a valid manifest whose kinds are all classified not guidance offers no candidate, even when the
	 * attempt changed a file.
	 */
	@Test
	public void notGuidanceKindsProduceNoCandidate()
	{
		// Requirement: R-D10
		Path path = Path.of("rules/safety.md");
		Hunk changed = new Hunk(path, 1, 2, 3, 2, 4);
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
				new PromptAuthorKind("judge outage", List.of("f"), FailureKind.NOT_GUIDANCE, List.of(), "")),
			List.of(new HunkEvidence("h1", path, 2, 3, 2, 4, List.of("f"), "Another read", "plain_list")));

		requireThat(violations(manifest, Set.of("f"), List.of(changed)), "violations").isEmpty();
		requireThat(manifest.producesCandidate(List.of(changed)), "producesCandidate").isFalse();
	}

	/**
	 * Verifies that a test defect kind is read with the fault that it names in the test.
	 */
	@Test
	public void readsTestDefectFault()
	{
		// Requirement: R-D10
		JsonMapper json = JsonMapper.builder().build();
		PromptAuthorPrompt prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of());
		String response = """
			{"needed_edits":[],"kinds":[{"name":"named the test","failures":["f"],"classification":"test_defect",
			 "hunks":[],"fault":"The turn tells the agent that it is tested."}],"hunks":[],"conflicts":[]}
			""";
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript").toAbsolutePath(),
			List.of(List.of(response)), Path.of("/effects").toAbsolutePath(), Path.of("/initial-project").toAbsolutePath(),
			"model", null, null, List.of(), "none", List.of());

		PromptAuthorKind kind = prompt.report(result).orElseThrow().manifest().kinds().getFirst();

		requireThat(kind.classification(), "classification").isEqualTo(FailureKind.TEST_DEFECT);
		requireThat(kind.fault(), "fault").isEqualTo("The turn tells the agent that it is tested.");
	}

	/**
	 * Verifies that a test defect kind must name the fault of the test.
	 */
	@Test
	public void testDefectNeedsFault()
	{
		// Requirement: R-D10
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
			new PromptAuthorKind("named the test", List.of("f"), FailureKind.TEST_DEFECT, List.of(), " ")), List.of());

		requireThat(violations(manifest, Set.of("f"), List.of()).toString(), "violations").contains("fault");
	}

	/**
	 * Verifies that a failure of a test that a test revision reported sound cannot be classified a test defect again.
	 */
	@Test
	public void soundTestIsNoTestDefect()
	{
		// Requirement: R-D10
		PromptAuthorManifest manifest = new PromptAuthorManifest(List.of(
			new PromptAuthorKind("named the test", List.of("f"), FailureKind.TEST_DEFECT, List.of(),
				"The turn tells the agent that it is tested.")), List.of());

		List<String> violations = manifest.violations(Set.of("f"), List.of(), ContractFixtures.contract(),
			new FileInventory(Path.of("/project").toAbsolutePath(), Map.of()), Map.of(), List.of(), Map.of("f", "safety"));

		requireThat(violations.toString(), "violations").contains("safety").contains("sound");
	}

	/**
	 * Returns the rejection reasons of a manifest that reports no conflict, against the fixture contract.
	 *
	 * @param manifest the manifest
	 * @param received the supplied failure identifiers
	 * @param changed the actual changed hunks
	 * @return the rejection reasons
	 */
	private static List<String> violations(PromptAuthorManifest manifest, Set<String> received, List<Hunk> changed)
	{
		return manifest.violations(received, changed, ContractFixtures.contract(),
			new FileInventory(Path.of("/project").toAbsolutePath(), Map.of()), Map.of(), List.of(), Map.of());
	}

	/**
	 * Verifies that the prompt author is asked to name, as each change's dissimilar case, a test of the confirmed
	 * contract.
	 */
	@Test
	public void asksForAContractTestAsTheDissimilarCase()
	{
		// Requirement: R-D17
		JsonMapper json = JsonMapper.builder().build();
		PromptAuthorPrompt prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(Path.of("CLAUDE.md")),
			Set.of());
		PromptAuthorBrief brief = new PromptAuthorBrief("safety", PromptAuthorApproach.CORRECT_INSTRUCTION,
			ContractFixtures.contract(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), "", List.of(), List.of(),
			List.of());
		String turn = prompt.request(brief, Path.of("/copy").toAbsolutePath()).turns().getFirst();

		requireThat(turn, "instructions").contains("the `id` of a test in the confirmed contract").
			contains("\"plain_list\"");
	}

	/**
	 * Returns Java path parsing boundaries that the prompt author schema must also describe.
	 *
	 * @return path and acceptance pairs
	 */
	@DataProvider
	public static Object[][] pathBoundaries()
	{
		return new Object[][]
			{
				{" ", true}, {"..notes.md", true}, {"rules//x.md", true}, {"rules/x.md/", true},
				{"../notes.md", false}, {"rules/./x.md", false}, {"rules/../x.md", false}, {"", false}
			};
	}

	/**
	 * Verifies that the schema and Java reader accept the same parsed-path boundaries.
	 *
	 * @param path the report path
	 * @param accepted whether it is accepted
	 * @throws IOException if the schema cannot be read
	 */
	@Test(dataProvider = "pathBoundaries")
	public void schemaMatchesParsedPaths(String path, boolean accepted) throws IOException
	{
		// Requirement: R-D10
		JsonMapper json = JsonMapper.builder().build();
		PromptAuthorPrompt prompt = new PromptAuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of());
		String response = """
			{"needed_edits":[{"requirement":"R1","path":"%s","operation":"create"}],
			 "kinds":[],"hunks":[],"conflicts":[]}
			""".formatted(path);
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript").toAbsolutePath(),
			List.of(List.of(response)), Path.of("/effects").toAbsolutePath(), Path.of("/initial-project").toAbsolutePath(),
			"model", null, null, List.of(), "none", List.of());
		requireThat(prompt.report(result).isPresent(), "reader").isEqualTo(accepted);
		String schema = PromptTemplateSecrets.access().readResource("/optimizer/prompt-author/report.schema.json");
		String pattern = json.readTree(schema).get("$defs").get("path").get("pattern").asString();
		requireThat(Pattern.compile(pattern).matcher(path).find(), "schema").isEqualTo(accepted);
	}

	/**
	 * Verifies that delimiter characters inside one turn cannot collide with two separate turns.
	 */
	@Test
	public void citationIdentityPreservesTurnBoundaries()
	{
		// Requirement: R-D10
		DrawnTestCase original = Builder.targeted("safety", "cache");
		DrawnTestCase one = new DrawnTestCase(original.cellId(), original.testId(),
			original.testCase(), List.of("a, b"), original.expectedBehaviour(), original.generator(),
			original.streamKind(), original.streamPosition(), original.targeted());
		DrawnTestCase two = new DrawnTestCase(original.cellId(), original.testId(),
			original.testCase(), List.of("a", "b"), original.expectedBehaviour(), original.generator(),
			original.streamKind(), original.streamPosition(), original.targeted());
		requireThat(PromptAuthorManifest.drawnTestCaseId(one), "oneTurn").
			isNotEqualTo(PromptAuthorManifest.drawnTestCaseId(two));
	}

	/**
	 * Verifies that repeated development failures of one test case retain separate classifications, and that the
	 * failure summaries of ended attempts are not classified.
	 */
	// Requirement: R-D3
	@Test
	public void separatesFailureOccurrences()
	{
		// Requirement: R-D10
		// Requirement: R-D9
		Failure failure = new Failure(Builder.targeted("safety", "cache"), "failure", List.of(), null, List.of(),
			List.of(), Builder.FILES);
		PromptAuthorBrief brief = new PromptAuthorBrief("safety", PromptAuthorApproach.CORRECT_INSTRUCTION,
			ContractFixtures.contract(), List.of(failure, failure), Map.of(), Map.of(),
			List.of(new PromptAuthorBrief.EndedAttempt(1, Outcome.BELOW_MINIMUM, Map.of(), List.of(failure.summary()),
				List.of(), List.of())),
			Map.of(), "", List.of(), List.of(), List.of());
		requireThat(brief.failureIds(), "classifiedFailures").
			isEqualTo(Set.of(brief.developmentId(0), brief.developmentId(1)));
		requireThat(brief.developmentId(0), "firstOccurrence").isNotEqualTo(brief.developmentId(1));
	}
}
