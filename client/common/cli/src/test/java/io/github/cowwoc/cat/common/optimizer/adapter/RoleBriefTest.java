package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ProjectRuleLoader;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.fakes.RoleRuleFixtures;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorApproach;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.execution.AuthorBrief;
import io.github.cowwoc.cat.common.optimizer.execution.prompt.AuthorPrompt;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.proposal.DeriverProtocol;
import io.github.cowwoc.cat.common.optimizer.proposal.JudgeTemplate;
import io.github.cowwoc.cat.common.optimizer.proposal.RegressionScenarios;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness;
import io.github.cowwoc.cat.common.runner.fakes.ScriptedHarness.Turn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Tests the complete role briefs that {@link RunnerSessions} sends to its bridge.
 */
public final class RoleBriefTest
{
	private static final Path TEMP = Path.of(System.getProperty("cat.work.temp"));
	private final JsonMapper json = JsonMapper.builder().build();
	private final ProposalSchema schema = new ProposalSchema(json);

	/**
	 * Creates the test.
	 */
	public RoleBriefTest()
	{
	}

	/**
	 * Sends a request through an in-memory bridge and returns its observed prompts.
	 *
	 * @param root the test-owned run root
	 * @param artifact the installed fixture artifact
	 * @param request the role request
	 * @return the ordered prompts received by the bridge
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	private static List<String> deliver(Path root, Path artifact, SessionRequest request)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		List<String> prompts = new CopyOnWriteArrayList<>();
		RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, root.resolve("runs"), "", true,
			Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
		TestSessions sessions = new TestSessions(settings, (options, input, output) ->
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), TestSessions.HARNESS,
				request.turns().stream().map(_ -> Turn.replies("ok")).toList(), input, output, prompts));

		SessionResult result = sessions.start(request).completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
		requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
		return List.copyOf(prompts);
	}

	/**
	 * Verifies that the author receives all six rules rendered from its installed artifact.
	 *
	 * @throws IOException if a fixture cannot be written or rendered
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	@Test
	public void authorReceivesCompleteRenderedRules()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-role-brief-"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			AuthorPrompt author = new AuthorPrompt(json, schema, Set.of(Path.of("AGENTS.md")), Set.of());
			AuthorBrief brief = new AuthorBrief("safety", AuthorApproach.CORRECT_INSTRUCTION,
				ContractFixtures.contract(), List.of(), Map.of(), List.of(), Set.of(), Map.of(), "");

			String delivered = deliver(root, artifact, author.request(brief, root)).getFirst();

			ProjectRuleLoader loader = new ProjectRuleLoader(YAMLMapper.builder().build());
			for (String rule : RoleRuleFixtures.AUTHOR_RULES)
			{
				requireThat(delivered, "brief").contains(loader.loadRule(artifact,
					Path.of("include/common/" + rule + ".md")));
			}
			Path companion = artifact.resolve("rules/include/common/companion.md").toAbsolutePath().normalize();
			requireThat(delivered, "brief").contains(companion.toString()).
				doesNotContain("${CAT_PLUGIN_ROOT}").doesNotContain("<!-- cat:plugin-root -->").
				doesNotContain("<!-- cat:include").doesNotContain("name: fixture").doesNotContain("\n{{rules}}\n");
		}
	}

	/**
	 * Verifies that only contract-design is delivered to the deriver and its output turn remains unchanged.
	 *
	 * @throws IOException if a fixture cannot be written or rendered
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	@Test
	public void deriverReceivesOnlyContractDesign()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-role-brief-"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			DeriverProtocol deriver = new DeriverProtocol(json, schema,
				new JudgeTemplate(json, new TrialProfile("role-model", "high")), new RegressionScenarios(root, List.of()));
			SessionRequest request = deriver.request(root, ContractFixtures.contract().profiles().inventory());

			List<String> delivered = deliver(root, artifact, request);

			ProjectRuleLoader loader = new ProjectRuleLoader(YAMLMapper.builder().build());
			requireThat(delivered.getFirst(), "brief").contains(loader.loadRule(artifact,
				Path.of("include/common/contract-design.md")));
			for (String rule : RoleRuleFixtures.AUTHOR_RULES)
			{
				if (!rule.equals("contract-design"))
					requireThat(delivered.getFirst(), "brief").doesNotContain("Complete guidance for " + rule);
			}
			requireThat(delivered.getFirst(), "brief").doesNotContain("\n{{rules}}\n");
			requireThat(delivered.getLast(), "outputTurn").isEqualTo(request.turns().getLast());
		}
	}

	/**
	 * Verifies that judge and trial requests receive no role rules, including when their artifact has no rules.
	 *
	 * @throws IOException if a fixture cannot be written
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	@Test
	public void judgeAndTrialBriefsRemainUnchanged()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-role-brief-"));
			for (SessionRole role : List.of(SessionRole.JUDGE, SessionRole.TRIAL))
			{
				SessionRequest request = new SessionRequest(role, root, List.of("Frozen brief {{rules}}"), List.of(),
					Duration.ofMinutes(1), TrialProfile.HARNESS_DEFAULT);
				requireThat(deliver(root, root.resolve("missing-artifact"), request), "briefs").isEqualTo(request.turns());
			}
		}
	}

	/**
	 * Verifies that a role brief without a rules placeholder receives an appended rules section.
	 *
	 * @throws IOException if a fixture cannot be written
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the session fails
	 * @throws TimeoutException if the session does not finish
	 */
	@Test
	public void appendsRulesWhenTheBriefHasNoPlaceholder()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-role-brief-"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			for (SessionRole role : List.of(SessionRole.AUTHOR, SessionRole.DERIVER))
			{
				SessionRequest request = new SessionRequest(role, root, List.of("Saved brief"), List.of(),
					Duration.ofMinutes(1), TrialProfile.HARNESS_DEFAULT);
				requireThat(deliver(root, artifact, request).getFirst(), "brief").startsWith("Saved brief\n\n## Rules\n\n").
					contains("Complete guidance for contract-design");
			}
		}
	}

	/**
	 * Verifies that a missing author rule fails naming its artifact file.
	 *
	 * @throws IOException if a fixture cannot be written
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if the rule cannot be read
	 * @throws TimeoutException if the session does not finish
	 */
	@Test(expectedExceptions = ExecutionException.class,
		expectedExceptionsMessageRegExp = ".*rules/include/common/prompt-file-references\\.md.*")
	public void missingAuthorRuleNamesItsFile()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E20
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "cat-role-brief-"));
			Path artifact = RoleRuleFixtures.write(root.resolve("artifact"));
			Files.delete(artifact.resolve("rules/include/common/prompt-file-references.md"));
			SessionRequest request = new SessionRequest(SessionRole.AUTHOR, root, List.of("Brief {{rules}}"), List.of(),
				Duration.ofMinutes(1), TrialProfile.HARNESS_DEFAULT);

			deliver(root, artifact, request);
		}
	}
}
