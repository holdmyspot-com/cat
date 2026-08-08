package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

/**
 * Tests {@link RoutePreparationInput}.
 */
public final class RoutePreparationInputTest
{
	private final HarnessRunnerScope scope = new HarnessRunnerScope();

	/**
	 * Creates the test fixture.
	 */
	public RoutePreparationInputTest()
	{
	}

	/**
	 * Closes the runner scope after this test class finishes.
	 */
	@AfterClass(alwaysRun = true)
	public void closeScope()
	{
		scope.close();
	}

	/**
	 * Verifies ordinary CAT preparation precedes, but does not replace, the caller's first task turn.
	 *
	 * @throws IOException if bridge input cannot be read
	 */
	@Test
	public void prependsRoutePreparationBeforeFirstTurn() throws IOException
	{
		String source = "{\"type\":\"turn\",\"prompt\":\"Implement a TOML reader.\"}\n" +
			"{\"type\":\"complete\"}\n";

		RoutePreparationInput.PreparedInput prepared = RoutePreparationInput.prepare(new StringReader(source),
			Path.of("installed-rules"), scope);

		String normalized = prepared.jsonl().replace("\\n", " ").replace("\\t", " ").replaceAll("\\s+", " ");
		requireThat(normalized, "preparedInput").contains("You are preparing to execute the later user task").
			contains("Implement a TOML reader.").contains("select only the routes whose own trigger is already true").
			contains("Do not pre-load a rule that might apply only after a later inspection").
			contains("unless the route explicitly governs reporting or verifying that fact").
			contains("A task-supplied fact still counts when the later response repeats it to the reader").
			contains("Treat the quoted later task as already received for route selection").
			contains("A later user-facing response is task work").
			contains("This is a selection turn, not a rule-reading turn").contains("selected_rules").
			doesNotContain("select and read every route");
		requireThat(prepared.jsonl(), "preparedInput").contains(source);
		requireThat(prepared.deliveryTurn(), "deliveryTurn").isEqualTo(0);
	}

	/**
	 * Verifies a selection response accepts only the explicit canonical-path protocol.
	 *
	 * @throws IOException if the selection cannot be parsed
	 */
	@Test
	public void parsesStrictSelectedRules()
		throws IOException
	{
		String response = "{\"selected_rules\":[\"include/common/write-clearly.md\"]}";
		List<String> selectedRules = RoutePreparationInput.selectedRules(response, scope);
		requireThat(selectedRules, "selectedRules").isEqualTo(List.of("include/common/write-clearly.md"));
	}

	/**
	 * Verifies an informal preparation acknowledgement cannot authorize a later task.
	 *
	 * @throws IOException if validation rejects the response
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "CAT route selection must be JSON")
	public void rejectsInformalRouteAcknowledgement() throws IOException
	{
		RoutePreparationInput.selectedRules("ROUTES READY", scope);
	}

	/**
	 * Verifies selections are paths below the installed rule root rather than paths that repeat that root name.
	 *
	 * @throws IOException if validation rejects the path
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "CAT route selection must contain distinct nonblank rule paths")
	public void rejectsRulesRootPrefix() throws IOException
	{
		RoutePreparationInput.selectedRules("{\"selected_rules\":[\"rules/include/common/example.md\"]}", scope);
	}

	/**
	 * Verifies a routing-only diagnostic executes the ordinary route-selection turn and stops before the later task.
	 *
	 * @throws IOException if bridge input cannot be read
	 */
	@Test
	public void completesRoutingOnlyAfterPreparation() throws IOException
	{
		String source = "{\"type\":\"turn\",\"prompt\":\"Implement a TOML reader.\",\"routing_only\":true}\n" +
			"{\"type\":\"complete\"}\n";

		RoutePreparationInput.PreparedInput prepared = RoutePreparationInput.prepare(new StringReader(source),
			Path.of("installed-rules"), scope);

		List<String> messages = prepared.jsonl().lines().toList();
		requireThat(messages.size(), "messages.size").isEqualTo(2);
		String normalized = messages.getFirst().replace("\\n", " ").replace("\\t", " ").replaceAll("\\s+", " ");
		requireThat(normalized, "routePreparation").contains("Implement a TOML reader.").
			contains("You are preparing to execute the later user task").doesNotContain("routing_only");
		requireThat(messages.get(1), "completion").isEqualTo("{\"type\":\"complete\"}");
	}

	/**
	 * Verifies a routing-only control cannot be disabled while still claiming the bounded diagnostic mode.
	 *
	 * @throws IOException if bridge input cannot be read
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "routing_only must be true when present")
	public void rejectsDisabledRoutingOnly() throws IOException
	{
		String source = "{\"type\":\"turn\",\"prompt\":\"Implement a TOML reader.\",\"routing_only\":false}\n";

		RoutePreparationInput.prepare(new StringReader(source), Path.of("installed-rules"), scope);
	}

	/**
	 * Verifies an uninstalled CAT route leaves the caller's bridge input unchanged.
	 *
	 * @throws IOException if bridge input cannot be read
	 */
	@Test
	public void leavesInputUnchangedWithoutCat() throws IOException
	{
		String source = "{\"type\":\"turn\",\"prompt\":\"Implement a TOML reader.\"}\n" +
			"{\"type\":\"complete\"}\n";

		RoutePreparationInput.PreparedInput prepared = RoutePreparationInput.prepare(new StringReader(source), null, scope);

		requireThat(prepared.jsonl(), "preparedInput").isEqualTo(source);
		requireThat(prepared.deliveryTurn(), "deliveryTurn").isEqualTo(0);
	}

	/**
	 * Verifies delivered-rule records reject a missing selected path.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsDeliveredRuleWithoutPath()
	{
		new RoutePreparationInput.DeliveredRule("", "sha256");
	}

	/**
	 * Verifies prepared input snapshots a direct-delivery receipt instead of retaining the caller's mutable list.
	 */
	@Test
	public void snapshotsDirectDeliveryReceipt()
	{
		List<RoutePreparationInput.DeliveredRule> supplied = new ArrayList<>();
		supplied.add(new RoutePreparationInput.DeliveredRule("include/common/prefer-reuse.md", "sha256"));

		RoutePreparationInput.PreparedInput prepared = new RoutePreparationInput.PreparedInput("{}", supplied, 1);
		supplied.clear();

		requireThat(prepared.deliveredRules().size(), "prepared.deliveredRules.size").isEqualTo(1);
	}

	/**
	 * Verifies a delivery position cannot claim rules were sent when its receipt is empty.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsDeliveryTurnWithoutReceipt()
	{
		new RoutePreparationInput.PreparedInput("{}", List.of(), 1);
	}

	/**
	 * Verifies declared rules are inserted into the pre-task prompt and retained in its receipt before transport.
	 *
	 * @throws IOException if temporary rule setup or preparation fails
	 */
	@Test
	public void insertsDeclaredRulesBeforeTask() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));
			Path first = ruleRoot.resolve("include/common/first.md");
			Path second = ruleRoot.resolve("include/common/second.md");
			Files.createDirectories(first.getParent());
			Files.writeString(first, "First rule.");
			Files.writeString(second, "Second rule.");
			String source = "{\"type\":\"turn\",\"prompt\":\"Implement a TOML reader.\",\"route_preparation\":[" +
				"\"include/common/first.md\",\"include/common/second.md\"]}\n{\"type\":\"complete\"}\n";

			RoutePreparationInput.PreparedInput prepared = RoutePreparationInput.prepare(new StringReader(source), ruleRoot,
				scope);

			String firstRequest = prepared.jsonl().lines().findFirst().orElseThrow();
			String routePrompt;
			try (ToolScope scope = new ToolScope())
			{
				routePrompt = scope.json().readTree(firstRequest).path("prompt").asString();
			}
			requireThat(routePrompt, "routePrompt").contains("--- include/common/first.md ---\nFirst rule.").
				contains("--- include/common/second.md ---\nSecond rule.").doesNotContain("CAT_RULE_CONTENTS");
			requireThat(prepared.jsonl(), "preparedInput").doesNotContain("route_preparation");
			requireThat(prepared.deliveryTurn(), "deliveryTurn").isEqualTo(1);
			requireThat(prepared.deliveredRules().size(), "deliveredRules.size").isEqualTo(2);
			requireThat(prepared.deliveredRules().get(0).relativePath(), "firstPath").isEqualTo("include/common/first.md");
		}
	}

	/**
	 * Verifies callers can resolve one or more declared files before placing their text in a request.
	 *
	 * @throws IOException if temporary rule setup or resolution fails
	 */
	@Test
	public void resolvesRuleFilesBeforeConstruction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));
			Path rule = ruleRoot.resolve("include/common/first.md");
			Files.createDirectories(rule.getParent());
			Files.writeString(rule, "First rule.");

			RoutePreparationInput.ResolvedRules resolved = RoutePreparationInput.resolveRules(ruleRoot,
				List.of("include/common/first.md"));

			requireThat(resolved.contents(), "contents").isEqualTo("\n--- include/common/first.md ---\nFirst rule.");
			requireThat(resolved.deliveredRules().size(), "deliveredRules.size").isEqualTo(1);
		}
	}

	/**
	 * Verifies a route cannot escape the caller-supplied installed rule root.
	 *
	 * @throws IOException if the temporary rule root cannot be created
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "CAT direct rule delivery contains an invalid relative rule path")
	public void rejectsTraversalInDeclaredRulePath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));

			RoutePreparationInput.resolveRules(ruleRoot, List.of("../outside.md"));
		}
	}

	/**
	 * Verifies an unavailable declared rule fails before the later task can use a partial route.
	 *
	 * @throws IOException if the temporary rule root cannot be created
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "CAT direct rule delivery cannot find declared rule: include/common/missing.md")
	public void rejectsUnavailableDeclaredRule() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path ruleRoot = cleanup.add(Files.createTempDirectory("route-preparation-rules-"));

			RoutePreparationInput.resolveRules(ruleRoot, List.of("include/common/missing.md"));
		}
	}
}
