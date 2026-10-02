package io.github.cowwoc.cat.common.optimizer.execution.prompt;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorManifest;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import tools.jackson.databind.json.JsonMapper;
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
			{"needed_edits": [], "classifications": [], "hunks": []}
			""";
		SessionResult.Completed result = new SessionResult.Completed(Path.of("/transcript"), List.of(List.of(response)),
			Path.of("/effects"), "model", null, null, List.of(), "none", List.of());
		requireThat(prompt.neededEdits(result).isPresent(), "accepted").isTrue();
	}

	/**
	 * Verifies that the author receives all four evidence-based editing steps.
	 */
	@Test
	public void explainsClassificationAndGeneralization()
	{
		// Requirement: R-D10
		JsonMapper json = JsonMapper.builder().build();
		AuthorPrompt prompt = new AuthorPrompt(json, new ProposalSchema(json), Set.of(Path.of("CLAUDE.md")),
			Set.of());
		AuthorBrief brief = new AuthorBrief("safety", ContractFixtures.contract(), List.of(), Map.of(), List.of(),
			Set.of(), Map.of());
		String turn = prompt.request(brief, Path.of("/copy")).turns().getFirst();
		requireThat(turn, "instructions").contains("routing").contains("not guidance").contains("smallest").
			contains("contradiction").contains("analogous").contains("dissimilar").contains("manifest");
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
			 "classifications":[],"hunks":[]}
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
		TrialInstance original = CampaignBuilder.targeted("safety", "cache");
		TrialInstance one = new TrialInstance(original.cellId(), original.categoryId(), original.testId(),
			original.choices(), List.of("a, b"), original.expectedBehaviour(), original.generator(),
			original.streamKind(), original.streamPosition(), original.targeted());
		TrialInstance two = new TrialInstance(original.cellId(), original.categoryId(), original.testId(),
			original.choices(), List.of("a", "b"), original.expectedBehaviour(), original.generator(),
			original.streamKind(), original.streamPosition(), original.targeted());
		requireThat(AuthorManifest.instanceId(one), "oneTurn").isNotEqualTo(AuthorManifest.instanceId(two));
	}

	/**
	 * Verifies that repeated instances from different ended attempts retain separate classifications.
	 */
	@Test
	public void separatesFailureOccurrences()
	{
		// Requirement: R-D10
		Failure failure = new Failure(CampaignBuilder.targeted("safety", "cache"), "failure", List.of(), List.of());
		AuthorBrief brief = new AuthorBrief("safety", ContractFixtures.contract(), List.of(failure), Map.of(),
			List.of(new AuthorBrief.EndedAttempt(1, Outcome.BELOW_MINIMUM, Map.of(), List.of(failure)),
				new AuthorBrief.EndedAttempt(2, Outcome.BELOW_MINIMUM, Map.of(), List.of(failure))), Set.of(), Map.of());
		requireThat(brief.failureIds().size(), "classifiedFailures").isEqualTo(3);
		requireThat(brief.endedId(1, 0), "firstAttempt").isNotEqualTo(brief.endedId(2, 0));
	}
}
