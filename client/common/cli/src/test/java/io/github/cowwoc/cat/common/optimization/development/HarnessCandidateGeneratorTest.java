package io.github.cowwoc.cat.common.optimization.development;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests bounded harness-backed failure diagnosis and target-only candidate generation.
 */
public final class HarnessCandidateGeneratorTest
{
	private static final String PRIVATE_PROMPT = "Customer-specific architecture preference";
	private final ToolScope scope = new ToolScope();

	/**
	 * Creates the test fixture.
	 */
	public HarnessCandidateGeneratorTest()
	{
	}

	/**
	 * Closes the shared JSON scope.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies the generator keeps private evidence out of trusted instructions and returns bounded target revisions.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void generatesBoundedTargetRevisions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-generator-"));
			Inputs inputs = inputs(root);
			HarnessModelProfile profile = new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM);
			List<String> prompts = new ArrayList<>();
			List<Path> readOnlyEvidence = new ArrayList<>();
			HarnessCandidateGenerator generator = new HarnessCandidateGenerator(scope.json(), profile,
				root.resolve("generation"), 2, (selectedProfile, prompt, evidence, runRoot) ->
				{
					prompts.add(prompt);
					readOnlyEvidence.addAll(evidence);
					return request(selectedProfile, prompt, evidence, runRoot);
				}, request -> execution(request, response()));

			HarnessCandidateGenerator.Proposal proposal = generator.generate(new HarnessCandidateGenerator.Inputs(
				inputs.champion().bundleRoot(), inputs.contractRoot(), "contract.json", inputs.failures(), "common/rule.md",
				Set.of("clarity")));

			requireThat(proposal.candidates(), "candidates").size().isEqualTo(2);
			requireThat(proposal.candidates().getFirst().replacement(), "replacement").contains("Use direct language");
			requireThat(proposal.diagnoses().getFirst().surroundingArtifacts(), "surroundingArtifacts").
				containsExactly(List.of("common/architecture.md"));
			requireThat(prompts.getFirst(), "trustedPrompt").doesNotContain(PRIVATE_PROMPT);
			requireThat(readOnlyEvidence, "readOnlyEvidence").contains(inputs.champion().bundleRoot()).
				contains(inputs.contractRoot()).contains(inputs.failures());
		}
	}

	/**
	 * Verifies generated target replacements become immutable bundles while unchanged companion rules are preserved.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void materializesImmutableCandidateBundles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-materializer-"));
			Inputs inputs = inputs(root);
			HarnessCandidateGenerator.Proposal proposal = new HarnessCandidateGenerator.Proposal(List.of(), List.of(
				new HarnessCandidateGenerator.CandidateProposal("candidate-1", "Clarify the failure boundary.",
					"Use direct language and cite the decision.")));

			List<DevelopmentComparisonOperation.CandidateReference> candidates =
				CandidateProposalMaterializer.materialize(scope.json(), inputs.champion(), proposal,
					root.resolve("candidates"));
			List<DevelopmentComparisonOperation.CandidateReference> retried =
				CandidateProposalMaterializer.materialize(scope.json(), inputs.champion(), proposal,
					root.resolve("candidates"));

			CandidateBundle.Candidate materialized = CandidateBundle.read(scope.json(),
				candidates.getFirst().bundleRoot());
			requireThat(retried, "retried").containsExactly(candidates);
			requireThat(Files.readString(materialized.primary().source()), "primaryRule").
				isEqualTo("Use direct language and cite the decision.");
			CandidateBundle.Entry companion = materialized.entries().stream().
				filter(entry -> entry.relativePath().equals("common/companion.md")).findFirst().orElseThrow();
			requireThat(Files.readString(companion.source()), "companionRule").isEqualTo("Preserve this companion.");
		}
	}

	/**
	 * Verifies an existing candidate set cannot be reused for changed generation inputs.
	 *
	 * @throws IOException for the expected identity rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Candidate-set input differs from retained identity.*")
	public void rejectsChangedCandidateSetRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-materializer-retry-"));
			Inputs inputs = inputs(root);
			HarnessCandidateGenerator.Proposal original = new HarnessCandidateGenerator.Proposal(List.of(), List.of(
				new HarnessCandidateGenerator.CandidateProposal("candidate-1", "First rationale.", "First revision.")));
			Path destination = root.resolve("candidates");
			CandidateProposalMaterializer.materialize(scope.json(), inputs.champion(), original, destination);
			HarnessCandidateGenerator.Proposal changed = new HarnessCandidateGenerator.Proposal(List.of(), List.of(
				new HarnessCandidateGenerator.CandidateProposal("candidate-1", "Changed rationale.", "Changed revision.")));

			CandidateProposalMaterializer.materialize(scope.json(), inputs.champion(), changed, destination);
		}
	}

	/**
	 * Verifies the request factory cannot substitute another candidate-generation task.
	 *
	 * @throws IOException for the expected request-binding rejection
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*changed the trusted prompt input.*")
	public void rejectsChangedGeneratorInput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("candidate-generator-input-"));
			Inputs inputs = inputs(root);
			HarnessModelProfile profile = new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM);
			HarnessCandidateGenerator generator = new HarnessCandidateGenerator(scope.json(), profile,
				root.resolve("generation"), 2, (selectedProfile, prompt, evidence, runRoot) ->
					request(selectedProfile, "Different task", evidence, runRoot), request ->
						execution(request, response()));

			generator.generate(new HarnessCandidateGenerator.Inputs(inputs.champion().bundleRoot(),
				inputs.contractRoot(), "contract.json", inputs.failures(), "common/rule.md", Set.of("clarity")));
		}
	}

	/** Verifies unsupported optimizer profiles are rejected at construction. */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*Unsupported OPTIMIZER_EXECUTOR model profile.*")
	public void rejectsUnsupportedOptimizerProfile()
	{
		new HarnessCandidateGenerator(scope.json(), new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.HIGH),
			Path.of("operation"), 1, this::request, request -> null);
	}

	/**
	 * Creates retained generator inputs.
	 *
	 * @param root the fixture root
	 * @return the inputs
	 * @throws IOException if fixture files cannot be written
	 */
	private Inputs inputs(Path root) throws IOException
	{
		Path source = Files.createDirectories(root.resolve("source/common"));
		Files.writeString(source.resolve("rule.md"), PRIVATE_PROMPT);
		Files.writeString(source.resolve("companion.md"), "Preserve this companion.");
		Path bundle = root.resolve("champion");
		CandidateBundle.Candidate candidate = CandidateBundle.materialize(scope.json(), source.getParent(), bundle,
			"common/rule.md", List.of("common/companion.md"));
		Path contractRoot = Files.createDirectories(root.resolve("contract"));
		Path contract = contractRoot.resolve("contract.json");
		Files.writeString(contract, "{\"contract\":true}\n");
		Path failures = Files.createDirectories(root.resolve("failures"));
		Files.writeString(failures.resolve("failure-1.txt"), "The response was indirect.");
		return new Inputs(new DevelopmentComparisonOperation.CandidateReference("champion", bundle, candidate.sha256()),
			contractRoot, failures);
	}

	/**
	 * Creates one typed harness request.
	 *
	 * @param profile the exact model profile
	 * @param prompt the trusted prompt
	 * @param evidence the read-only evidence
	 * @param runRoot the operation root
	 * @return the request
	 * @throws IOException if the project cannot be created
	 */
	private HarnessEvaluationRequest request(HarnessModelProfile profile, String prompt, List<Path> evidence,
		Path runRoot) throws IOException
	{
		Path project = Files.createDirectories(runRoot.resolve("project"));
		ObjectNode turn = scope.json().createObjectNode();
		turn.put("type", "turn");
		turn.put("prompt", prompt);
		String input = scope.json().writeValueAsString(turn) + "\n{\"type\":\"complete\"}\n";
		return new HarnessEvaluationRequest(runRoot.resolve("runner"), runRoot.resolve("artifact"), project, profile,
			input, runRoot.resolve("transcript.jsonl"), runRoot.resolve("receipt.json"), false, evidence);
	}

	/**
	 * Creates one retained model execution.
	 *
	 * @param request the request
	 * @param response the assistant response
	 * @return the execution
	 * @throws IOException if the transcript cannot be written
	 */
	private HarnessEvaluationExecution execution(HarnessEvaluationRequest request, String response) throws IOException
	{
		ObjectNode event = scope.json().createObjectNode();
		event.put("type", "turn-completed");
		event.put("runId", "generator");
		event.put("exitCode", 0);
		event.put("error", "");
		event.put("assistant_response", response);
		Files.createDirectories(request.transcript().getParent());
		Files.writeString(request.transcript(), scope.json().writeValueAsString(event) + "\n");
		return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(request.project(), request.transcript()),
			1, 2);
	}

	/**
	 * Returns one strict candidate-generation response.
	 *
	 * @return the JSON response
	 */
	private static String response()
	{
		return """
			{"diagnoses":[{"family_id":"clarity","cause":"The requirement is indirect.",
			"evidence_ids":["failure-1"],"surrounding_artifacts":["common/architecture.md"]}],
			"candidates":[
			{"candidate_id":"candidate-1","rationale":"Make the rule direct.",
			"replacement":"Use direct language and cite the decision."},
			{"candidate_id":"candidate-2","rationale":"State the output first.",
			"replacement":"Lead with the decision, then cite evidence."}]}
			""";
	}

	/**
	 * Holds one test input set.
	 *
	 * @param champion the immutable current candidate
	 * @param contractRoot the isolated contract evidence directory
	 * @param failures the failure evidence root
	 */
	private record Inputs(DevelopmentComparisonOperation.CandidateReference champion, Path contractRoot, Path failures)
	{
	}
}
