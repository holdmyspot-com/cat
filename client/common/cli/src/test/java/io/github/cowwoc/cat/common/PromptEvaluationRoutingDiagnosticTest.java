package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.classifiedFailure;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.runArtifact;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeFailingFormalBuildPlan;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.writeSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the non-certifying routing-diagnostic lifecycle.
 */
public final class PromptEvaluationRoutingDiagnosticTest
{
	/**
	 * Creates the test fixture.
	 */
	public PromptEvaluationRoutingDiagnosticTest()
	{
	}

	/**
	 * Verifies the public routing diagnostic prepares and completes the generated matrix without exposing an internal
	 * evaluator step to its caller.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void runsRetainedRoutingDiagnosticMatrix() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeRoutingMatrixRunner(root, true);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");

			ObjectNode result = PromptEvaluation.routingDiagnostic(scope.json(), temporary, sourceRun,
				"requested-change", 1, prompt, "fixture", context, runner, buildPlan,
				temporary.resolve("routing-diagnostic"), 3);

			requireThat(result.path("routing_matrix").path("status").asString(), "routingMatrixStatus").
				isEqualTo("passed");
			requireThat(runArtifact(Path.of(result.path("run").asString()), "suite"), "retainedDiagnosticSuite").
				isDirectory();
		}
	}

	/**
	 * Verifies a retained failed routing matrix records its routing-delivery failure in the workflow journal.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void journalsRoutingMatrixFailure() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); ToolScope scope = new ToolScope())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path sourceRun = classifiedFailure(root, suite, "requested-change", "routing-delivery");
			Path prompt = write(root, "prompt.md", "Prompt artifact\n");
			Path context = write(root, "context.md", "Validator contract\n");
			Path runner = writeRoutingMatrixRunner(root, false);
			Path buildPlan = writeFailingFormalBuildPlan(root);
			Path temporary = root.resolve(".cat/work/temp");

			ObjectNode result = PromptEvaluation.routingDiagnostic(scope.json(), temporary, sourceRun,
				"requested-change", 1, prompt, "fixture", context, runner, buildPlan,
				temporary.resolve("routing-diagnostic"), 3);

			requireThat(result.path("routing_matrix").path("status").asString(), "routingMatrixStatus").
				isEqualTo("failed");
			Path workRoot = temporary.getParent();
			ObjectNode handoff = PromptEvaluation.handoff(scope.json(), Path.of(result.path("run").asString()),
				Path.of(result.path("workspace").asString()), workRoot.resolve("retained-routing-failure"), temporary,
				workRoot);
			requireThat(Files.readString(Path.of(handoff.path("journal").asString())), "journal").
				contains("\"terminal_decision\" : \"ROUTING_MATRIX\"").
				contains("\"failure_class\" : \"routing-delivery\"");
		}
	}

	/**
	 * Verifies the routing audit requires every organic expectation to name a target owned by its router.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void auditsOrganicRouteOwnership() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path rules = Files.createDirectories(root.resolve("rules/common"));
			write(root, "rules/common/default-rules.md", "Read `rule.md` when the task needs it.\n");
			write(root, "rules/common/rule.md", "# Target\n");

			PromptEvaluation.run(new String[]{"audit-routing", root.resolve("rules").toString(),
				rules.resolve("default-rules.md").toString(), suite.toString()});
		}
	}

	/**
	 * Verifies the routing audit resolves plugin-root rule paths from the rules root.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void auditsPluginRootRulePaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-evaluation"));
			Path suite = writeSuite(root);
			Path manifest = suite.resolve("suite.json");
			Files.writeString(manifest, Files.readString(manifest).replace("common/rule.md", "include/common/rule.md"));
			Path router = write(root, "rules/common/default-rules.md",
				"Read $CAT_PLUGIN_ROOT/rules/include/common/rule.md when the task needs it.\n");
			write(root, "rules/include/common/rule.md", "# Target\n");

			PromptEvaluation.run(new String[]{"audit-routing", root.resolve("rules").toString(), router.toString(),
				suite.toString()});
		}
	}

	/**
	 * Writes a runner that completes every routing-matrix case with one routing-only verdict.
	 *
	 * @param root the fixture root
	 * @param correctRoutes whether each scenario reads or avoids its selected route as configured
	 * @return the executable runner path
	 * @throws IOException if fixture I/O fails
	 */
	private static Path writeRoutingMatrixRunner(Path root, boolean correctRoutes) throws IOException
	{
		Path cache = Files.createDirectories(root.resolve("routing-dependency-cache"));
		String selectedRoute = "other";
		if (correctRoutes)
			selectedRoute = "rule";
		Path runner = write(root, "routing-fixture-runner", """
			#!/bin/sh
			case "$1" in
			  --resolve-profile) printf 'owner=default\\nmodel=model-a\\neffort=medium\\n' ;;
			  --verify-dependency-cache) printf '%%s\\n' '%s' ;;
			  --verify-artifact) printf '%%s\\n' "$2" ;;
			  *)
			    project= output= receipt= executor=false
			    while [ "$#" -gt 0 ]; do
			      case "$1" in
			        --project) project="$2"; shift 2 ;;
			        --output) output="$2"; shift 2 ;;
			        --receipt) receipt="$2"; shift 2 ;;
			        --install-cat) executor=true; shift ;;
			        *) shift ;;
			      esac
			    done
			    mkdir -p "$project"
			    if [ "$executor" = true ]; then
			      event_started='{"type":"native-event","event":"{\\"method\\":\\"item/started\\",'
			      event_command='\\"params\\":{\\"item\\":{\\"type\\":\\"commandExecution\\",'
			      event_command_end='\\"id\\":\\"route\\",\\"command\\":\\"'
			      event_completed='\\"}}}"}'
			      completion_started='{"type":"native-event","event":"{\\"method\\":\\"item/completed\\",'
			      completion_end='\\",\\"status\\":\\"completed\\",\\"exitCode\\":0}}}"}'
			      case "$project" in
			        *unrelated-request*) route='cat /rules/common/other.md' ;;
			        *) route='cat /rules/common/%s.md' ;;
			      esac
			      printf '%%s%%s%%s%%s%%s\\n' "$event_started" "$event_command" "$event_command_end" \\
			        "$route" "$event_completed" > "$output"
			      printf '%%s%%s%%s%%s%%s\\n' "$completion_started" "$event_command" "$event_command_end" \\
			        "$route" "$completion_end" >> "$output"
			    else
			      printf validator > "$output"
			      verdicts="$project/verdicts.json"
			      printf '{"trial-1/requested-change":{"status":"passed","assertions":' > "$verdicts"
			      printf '{"routing-only":{"status":"passed","evidence":"fixture verdict"}}},' >> "$verdicts"
			      printf '"trial-1/unrelated-request":{"status":"passed","assertions":' >> "$verdicts"
			      printf '{"routing-only":{"status":"passed","evidence":"fixture verdict"}}},' >> "$verdicts"
			      printf '"trial-1/edge-condition":{"status":"passed","assertions":' >> "$verdicts"
			      printf '{"routing-only":{"status":"passed","evidence":"fixture verdict"}}}}' >> "$verdicts"
			    fi
			    printf '{"retained_project":"%%s","raw_result":"%%s"}' "$project" "$output" > "$receipt" ;;
			esac
			""".formatted(cache, selectedRoute));
		requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
		return runner;
	}
}
