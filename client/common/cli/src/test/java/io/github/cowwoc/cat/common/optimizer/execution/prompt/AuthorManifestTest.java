package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorManifest;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorConflict;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;
import java.nio.charset.StandardCharsets;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorApproach;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the author's evidence manifest and editing instructions.
 */
public final class AuthorManifestTest
{
	/**
	 * Creates the test.
	 */
	public AuthorManifestTest()
	{
	}

	/**
	 * Verifies that a complete author report can be read even when no edit is required.
	 */
	@Test
	public void readsCompleteManifest()
	{
		// Requirement: R-D10
		JsonMapper json = JsonMapper.builder().build();
		AuthorPrompt prompt = new AuthorPrompt(json, new ProposalSchema(json), Set.of(Path.of("CLAUDE.md")),
			Set.of());
		String response = """
			{"needed_edits": [], "classifications": [], "hunks": [], "conflicts": []}
			""";
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript"), List.of(List.of(response)),
			Path.of("/effects"), "model", null, null, List.of(), "none", List.of());
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
		AuthorPrompt prompt = new AuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of());
		String response = """
			{"needed_edits":[],"classifications":[{"instance":"f","classification":"conflict"}],"hunks":[],
			 "conflicts":[{"instances":["f"],"requirement_ids":["r1","r2"],
			 "quotations":[{"path":"CLAUDE.md","text":"Ask first."}],
			 "resolutions":["Keep asking","Allow deletion"],"recommended":1,"reason":"Keeps permission."}]}
			""";
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript"), List.of(List.of(response)),
			Path.of("/effects"), "model", null, null, List.of(), "none", List.of());
		requireThat(prompt.report(result).isPresent(), "accepted").isTrue();
		AuthorConflict conflict = prompt.report(result).orElseThrow().conflicts().getFirst();
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
				{"instances", "[]"}, {"requirement_ids", "[\"r1\"]"},
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
		AuthorPrompt prompt = new AuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of());
		JsonNode report = json.readTree("""
			{"needed_edits":[],"classifications":[],"hunks":[],"conflicts":[
			 {"instances":["f"],"requirement_ids":["r1","r2"],"quotations":[{"path":"CLAUDE.md","text":"Ask"}],
			 "resolutions":["First","Second"],"recommended":1,"reason":"Reason"}]}
			""");
		((ObjectNode) report.get("conflicts").get(0)).set(property, json.readTree(value));
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript"),
			List.of(List.of(json.writeValueAsString(report))), Path.of("/effects"), "model", null, null,
			List.of(), "none", List.of());
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
		FileInventory incumbent = new FileInventory(Path.of("/project"),
			Map.of(path, new FileEntry.RegularFile(content.hash(), Set.of())));
		AuthorConflict conflict = new AuthorConflict(List.of("f"), List.of("r1", "r2"),
			List.of(new AuthorConflict.Quotation(path, "Ask first.")), List.of("Keep asking", "Allow deletion"),
			1, "Keeps permission.");
		AuthorManifest manifest = new AuthorManifest(Map.of("f", FailureKind.CONFLICT), List.of());
		requireThat(manifest.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(path, content), List.of(conflict)), "confirmed").isEmpty();
		FileContent edited = new FileContent("Delete immediately.".getBytes(StandardCharsets.UTF_8));
		AuthorConflict introduced = new AuthorConflict(List.of("f"), List.of("r1", "r2"),
			List.of(new AuthorConflict.Quotation(path, "Delete immediately.")), conflict.resolutions(), 1,
			conflict.reason());
		requireThat(manifest.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(path, content), List.of(introduced)).toString(), "introduced").contains("CLAUDE.md");
		requireThat(manifest.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(path, edited), List.of(introduced)).toString(), "wrongSnapshot").contains("CLAUDE.md");
	}

	/**
	 * Verifies that classifications, contract ids and recommendation positions agree with conflict citations.
	 */
	@Test
	public void validatesConflictReferences()
	{
		// Requirement: R-D13
		FileInventory incumbent = new FileInventory(Path.of("/project"), Map.of());
		AuthorConflict conflict = new AuthorConflict(List.of("f"), List.of("r1", "unknown"),
			List.of(new AuthorConflict.Quotation(Path.of("missing.md"), "Missing text")),
			List.of("First", "Second"), 3, "Reason");
		AuthorManifest guidance = new AuthorManifest(Map.of("f", FailureKind.GUIDANCE), List.of());
		List<String> violations = guidance.violations(Set.of("f"), List.of(), ContractFixtures.contract(),
			incumbent, Map.of(), List.of(conflict));
		requireThat(violations.toString(), "violations").contains("not classified conflict").contains("unknown").
			contains("recommended").contains("missing.md");
		AuthorManifest classified = new AuthorManifest(Map.of("f", FailureKind.CONFLICT), List.of());
		requireThat(classified.violations(Set.of("f"), List.of(), ContractFixtures.contract(), incumbent,
			Map.of(), List.of()).toString(), "uncited").contains("no conflict");
	}

	/**
	 * Verifies that the author receives all four evidence-based editing steps.
	 */
	// Requirement: R-D3
	@Test
	public void explainsClassificationAndGeneralization()
	{
		// Requirement: R-D10
		JsonMapper json = JsonMapper.builder().build();
		AuthorPrompt prompt = new AuthorPrompt(json, new ProposalSchema(json), Set.of(Path.of("CLAUDE.md")),
			Set.of());
		AuthorBrief brief = new AuthorBrief("safety", AuthorApproach.CORRECT_INSTRUCTION,
			ContractFixtures.contract(), List.of(), Map.of(), Map.of(), List.of(),
			Map.of(), "", List.of());
		String turn = prompt.request(brief, Path.of("/copy")).turns().getFirst();
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
		AuthorManifest manifest = new AuthorManifest(Map.of("f", FailureKind.GUIDANCE), List.of(
			new AuthorManifest.Evidence(path, 2, 3, 2, 4, List.of("f"), "Another deletion request", dissimilar)));

		List<String> violations = manifest.violations(Set.of("f"), List.of(hunk), ContractFixtures.contract(),
			new FileInventory(Path.of("/project"), Map.of()), Map.of(), List.of());

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
	 * Verifies that the author is asked to name, as each change's dissimilar case, a test of the confirmed contract.
	 */
	@Test
	public void asksForAContractTestAsTheDissimilarCase()
	{
		// Requirement: R-D17
		JsonMapper json = JsonMapper.builder().build();
		AuthorPrompt prompt = new AuthorPrompt(json, new ProposalSchema(json), Set.of(Path.of("CLAUDE.md")),
			Set.of());
		AuthorBrief brief = new AuthorBrief("safety", AuthorApproach.CORRECT_INSTRUCTION,
			ContractFixtures.contract(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), "", List.of());
		String turn = prompt.request(brief, Path.of("/copy")).turns().getFirst();

		requireThat(turn, "instructions").contains("the `id` of a test in the confirmed contract").
			contains("\"plain_list\"");
	}

	/**
	 * Returns Java path parsing boundaries that the author schema must also describe.
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
		AuthorPrompt prompt = new AuthorPrompt(json, new ProposalSchema(json), Set.of(), Set.of());
		String response = """
			{"needed_edits":[{"requirement":"R1","path":"%s","operation":"create"}],
			 "classifications":[],"hunks":[],"conflicts":[]}
			""".formatted(path);
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript"), List.of(List.of(response)),
			Path.of("/effects"), "model", null, null, List.of(), "none", List.of());
		requireThat(prompt.report(result).isPresent(), "reader").isEqualTo(accepted);
		try (InputStream schema = AuthorPrompt.class.getResourceAsStream("/optimizer/author/report.schema.json"))
		{
			String pattern = json.readTree(schema).get("$defs").get("path").get("pattern").asString();
			requireThat(Pattern.compile(pattern).matcher(path).find(), "schema").isEqualTo(accepted);
		}
	}

	/**
	 * Verifies that delimiter characters inside one turn cannot collide with two separate turns.
	 */
	@Test
	public void citationIdentityPreservesTurnBoundaries()
	{
		// Requirement: R-D10
		TrialInstance original = Builder.targeted("safety", "cache");
		TrialInstance one = new TrialInstance(original.cellId(), original.categoryId(), original.testId(),
			original.choices(), List.of("a, b"), original.expectedBehaviour(), original.generator(),
			original.streamKind(), original.streamPosition(), original.targeted());
		TrialInstance two = new TrialInstance(original.cellId(), original.categoryId(), original.testId(),
			original.choices(), List.of("a", "b"), original.expectedBehaviour(), original.generator(),
			original.streamKind(), original.streamPosition(), original.targeted());
		requireThat(AuthorManifest.instanceId(one), "oneTurn").isNotEqualTo(AuthorManifest.instanceId(two));
	}

	/**
	 * Verifies that repeated development failures of one instance retain separate classifications, and that the
	 * failure summaries of ended attempts are not classified.
	 */
	// Requirement: R-D3
	@Test
	public void separatesFailureOccurrences()
	{
		// Requirement: R-D10
		// Requirement: R-D9
		Failure failure = new Failure(Builder.targeted("safety", "cache"), "failure", List.of(), null, List.of(),
			List.of());
		AuthorBrief brief = new AuthorBrief("safety", AuthorApproach.CORRECT_INSTRUCTION,
			ContractFixtures.contract(), List.of(failure, failure), Map.of(), Map.of(),
			List.of(new AuthorBrief.EndedAttempt(1, Outcome.BELOW_MINIMUM, Map.of(), List.of(failure.summary()))),
			Map.of(), "", List.of());
		requireThat(brief.failureIds(), "classifiedFailures").
			isEqualTo(Set.of(brief.developmentId(0), brief.developmentId(1)));
		requireThat(brief.developmentId(0), "firstOccurrence").isNotEqualTo(brief.developmentId(1));
	}
}
