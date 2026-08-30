package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import io.github.cowwoc.cat.common.evaluation.RuntimeVerification;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import tools.jackson.databind.node.ObjectNode;

/**
 * Creates reusable prompt-evaluation test inputs.
 */
final class PromptEvaluationFixtures
{
	static final String SEMANTIC_RESULT_ASSERTION =
		"\"assertions\":[{\"id\":\"result\",\"evaluation_kind\":\"semantic\",\"instruction\":" +
			"\"Verify the requested result.\"}]";

	/**
	 * Prevents construction of fixture operations.
	 */
	private PromptEvaluationFixtures()
	{
	}

	/**
	 * Writes a complete durable testcase suite and its prompt inputs.
	 *
	 * @param root the fixture directory
	 * @return the scenario-suite root
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeSuite(Path root) throws IOException
	{
		Path suite = root.resolve("suite");
		write(suite, "requested-change/scenario.md", """
			## Scenario

			Implement the requested behavior.

			## Assertions

			### result

			Verify the requested result.
			""");
		write(suite, "unrelated-request/scenario.md", """
			## Scenario

			Perform unrelated work.

			## Assertions

			### scope

			Verify the prompt did not invoke the target.
			""");
		write(suite, "edge-condition/scenario.md", """
			## Scenario

			Perform the edge case.

			## Assertions

			### edge-result

			Verify the edge-condition result.
			""");
		write(suite, "requested-change/project/project.txt", "requested behavior fixture\n");
		write(suite, "unrelated-request/project/project.txt", "scope preservation fixture\n");
		write(suite, "edge-condition/project/project.txt", "edge condition fixture\n");
		write(suite, "suite.json", """
			{
			  "schema_version": 3,
			  "purpose": "certification",
			  "routing_owner": "common/default-rules.md",
			  "sprt": {
			    "accept_pass_rate": 0.95,
			    "reject_pass_rate": 0.85,
			    "false_accept_rate": 0.05,
			    "false_reject_rate": 0.05
			  },
			  "scenarios": {
			    "requested-change": {
			     "measures":["organic_selection","application"],
			     "route_expectation":{"mode":"organic","selected":["common/rule.md"]}},
			    "unrelated-request": {
			     "measures":["organic_selection","application"],
			     "route_expectation":{"mode":"organic","not_selected":["common/rule.md"]}},
			    "edge-condition": {
			     "measures":["organic_selection","application"],
			     "route_expectation":{"mode":"organic","selected":["common/rule.md"]}}
			  }
			}
			""");
		return suite;
	}

	/**
	 * Writes a batch-validator execution plan that can pause during semantic-control calibration.
	 *
	 * @param root the fixture directory
	 * @param calibrationStarted a marker written when calibration begins, or {@code null} when no pause is required
	 * @param releaseCalibration a marker that releases calibration, or {@code null} when no pause is required
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeSemanticControlPlan(Path root, Path calibrationStarted, Path releaseCalibration)
		throws IOException
	{
		return PromptEvaluationExecutionFixtures.writeExecutionPlan(root);
	}

	/**
	 * Writes a batch-validator execution plan without pausing calibration.
	 *
	 * @param root the fixture directory
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeSemanticControlPlan(Path root) throws IOException
	{
		return writeSemanticControlPlan(root, null, null);
	}

	/**
	 * Writes one suite that declares an example detail reserved to its fixture.
	 *
	 * @param root the fixture directory
	 * @return the scenario-suite root
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeGeneralizationSuite(Path root) throws IOException
	{
		Path suite = writeSuite(root);
		Path manifest = suite.resolve("suite.json");
		String contents = Files.readString(manifest).replace("  \"purpose\": \"certification\",\n",
			"""
			  "purpose": "certification",
			  "generalization": {
			    "fixture_distinctives": [
			      {"case_id": "requested-change", "text": "empty value"}
			    ]
			  },
			""");
		Files.writeString(manifest, contents);
		return suite;
	}

	/**
	 * Writes the smallest immutable candidate bundle for one routed rule.
	 *
	 * @param root the fixture root
	 * @param primaryRule the canonical path below the runner's rules directory
	 * @param contents the evaluated rule text
	 * @return the candidate-bundle directory
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeCandidateBundle(Path root, String primaryRule, String contents) throws IOException
	{
		return writeCandidateBundle(root, primaryRule, contents, Map.of());
	}

	/**
	 * Writes the smallest immutable candidate bundle for one routed rule and its candidate-specific companions.
	 *
	 * @param root the fixture root
	 * @param primaryRule the canonical path below the runner's rules directory
	 * @param contents the evaluated rule text
	 * @param companions the additional canonical rule paths and contents
	 * @return the candidate-bundle directory
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeCandidateBundle(Path root, String primaryRule, String contents,
		Map<String, String> companions) throws IOException
	{
		return writeCandidateBundle(root, primaryRule, contents, companions, Map.of());
	}

	/**
	 * Writes the smallest immutable candidate bundle with its ordinary-to-candidate route mapping.
	 *
	 * @param root the fixture root
	 * @param primaryRule the canonical path below the runner's rules directory
	 * @param contents the evaluated rule text
	 * @param companions the additional canonical rule paths and contents
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical paths
	 * @return the candidate-bundle directory
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeCandidateBundle(Path root, String primaryRule, String contents,
		Map<String, String> companions, Map<String, String> routeReplacements) throws IOException
	{
		Path bundle = root.resolve("candidate-bundle");
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode manifest = scope.json().createObjectNode();
			manifest.put("primary_rule", primaryRule);
			ObjectNode routes = manifest.putObject("route_replacements");
			for (Map.Entry<String, String> replacement : routeReplacements.entrySet())
				routes.put(replacement.getKey(), replacement.getValue());
			write(bundle, "candidate-bundle.json", scope.json().writeValueAsString(manifest) + "\n");
		}
		write(bundle, "rules/" + primaryRule, contents);
		for (Map.Entry<String, String> companion : companions.entrySet())
			write(bundle, "rules/" + companion.getKey(), companion.getValue());
		return bundle;
	}

	/**
	 * Writes one fixture file below its root.
	 *
	 * @param root the fixture root
	 * @param relativePath the root-relative file path
	 * @param contents the file contents
	 * @return the written file
	 * @throws IOException if fixture I/O fails
	 */
	static Path write(Path root, String relativePath, String contents) throws IOException
	{
		Path file = root.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, contents);
		return file;
	}

	/**
	 * Reads one immutable artifact path from a persisted evaluation record.
	 *
	 * @param run the persisted evaluation record
	 * @param field the record field containing the artifact path
	 * @return the normalized artifact path
	 * @throws IOException if the record cannot be read
	 */
	static Path runArtifact(Path run, String field) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Path.of(scope.json().readTree(run.toFile()).path(field).asString()).toAbsolutePath().normalize();
		}
	}

	/**
	 * Locates the one validator group produced by a single-round fixture batch.
	 *
	 * @param batch the fixture batch workspace
	 * @return the group workspace
	 * @throws IOException if the batch did not produce exactly one group
	 */
	static Path validatorGroupWorkspace(Path batch) throws IOException
	{
		try (Stream<Path> entries = Files.list(batch))
		{
			List<Path> rounds = entries.filter(Files::isDirectory).filter(path -> path.getFileName().toString().startsWith(
				"validator-round-")).toList();
			if (rounds.size() != 1)
				throw new IOException("Expected exactly one validator round under " + batch + " but found " + rounds.size());
			Path group = rounds.getFirst().resolve("validator-group-1");
			if (!Files.isDirectory(group))
				throw new IOException("Expected the first validator group under " + rounds.getFirst());
			return group;
		}
	}

	/**
	 * Returns the append-only attempt ledger owned by one run record.
	 *
	 * @param run the record path
	 * @return its attempt-ledger path
	 */
	static Path attemptLedger(Path run)
	{
		return run.resolveSibling(run.getFileName() + ".attempts.jsonl");
	}

	/**
	 * Writes a candidate ledger that keeps one testcase's observed failure active for a later check.
	 *
	 * @param run the prepared evaluation record
	 * @param caseId the unresolved testcase
	 * @return the written candidate ledger
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeActiveFailureState(Path run, String caseId) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode record = (ObjectNode) scope.json().readTree(run.toFile());
			Path state = run.resolveSibling("candidate-state.json");
			ObjectNode candidateState = scope.json().createObjectNode();
			candidateState.put("schema_version", 2);
			candidateState.put("suite_sha256", record.path("suite_sha256").asString());
			candidateState.put("evaluation_epoch_sha256", record.path("evaluation_epoch_sha256").asString());
			candidateState.putArray("promotions");
			ObjectNode signature = candidateState.putArray("failure_signatures").addObject();
			signature.put("key", caseId + "/result/fixture");
			signature.putObject("latest_case").put("case_id", caseId).put("assertion", "result").
				put("boundary", "fixture");
			Files.writeString(state, scope.json().writeValueAsString(candidateState));
			record.put("failure_signature_state", state.toString());
			Files.writeString(run, scope.json().writeValueAsString(record));
			return state;
		}
	}

	/**
	 * Creates one completed native agent-message event for a raw-result fixture.
	 *
	 * @param text the completed message text
	 * @return one JSONL native-event record
	 * @throws IOException if the fixture event cannot be serialized
	 */
	static String nativeAgentMessage(String text) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			ObjectNode item = scope.json().createObjectNode();
			item.put("type", "agentMessage");
			item.put("text", text);
			ObjectNode parameters = scope.json().createObjectNode();
			parameters.set("item", item);
			ObjectNode event = scope.json().createObjectNode();
			event.put("method", "item/completed");
			event.set("params", parameters);
			ObjectNode envelope = scope.json().createObjectNode();
			envelope.put("type", "native-event");
			envelope.put("event", scope.json().writeValueAsString(event));
			return scope.json().writeValueAsString(envelope);
		}
	}

	/**
	 * Writes a runner that passes profile and artifact checks but makes each formal validator batch fail.
	 *
	 * @param root the fixture root
	 * @return the executable runner path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeFailingFormalRunner(Path root) throws IOException
	{
		return writeFormalRunner(root, "failed");
	}

	/**
	 * Writes a runner that supplies the requested formal validator outcome.
	 *
	 * @param root the fixture root
	 * @param formalVerdict the formal validator outcome
	 * @return the executable runner path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeFormalRunner(Path root, String formalVerdict) throws IOException
	{
		return writeFormalRunner(root, formalVerdict, false);
	}

	/**
	 * Writes a runner that supplies the requested formal validator outcome and can make every executor unavailable.
	 *
	 * @param root the fixture root
	 * @param formalVerdict the formal validator outcome
	 * @param blockExecutor whether executor invocations terminate unsuccessfully
	 * @return the executable runner path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeFormalRunner(Path root, String formalVerdict, boolean blockExecutor) throws IOException
	{
		Path cache = Files.createDirectories(root.resolve("dependency-cache"));
		String executorFailure = "";
		if (blockExecutor)
			executorFailure = "exit 17";
		Path runner = write(root, "formal-fixture-runner", """
			#!/bin/sh
			case "$1" in
			  --resolve-profile) printf 'harness=fixture\\nowner=default\\nmodel=model-a\\neffort=medium\\n' ;;
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
			    printf raw > "$output"
			    if [ "$executor" = true ]; then
			      %s
			      printf '{"retained_project":"%%s","raw_result":"%%s"}' "$project" "$output" > "$receipt"
			    else
			      case "$project" in
			        */check/*) verdict=passed ;;
			        *) verdict=%s ;;
			      esac
			      trial_segment=${project#*/trial-}
			      trial_number=${trial_segment%%/*}
			      case "$trial_number" in
			        ''|*[!0-9]*) trial_number=1 ;;
			      esac
			      printf '{"trial-%%s/requested-change":' "$trial_number" > "$project/verdicts.json"
			      printf '{"status":"%%s","assertions":' "$verdict" >> "$project/verdicts.json"
			      printf '{"result":{"status":"%%s","evidence":"fixture verdict"}}},' "$verdict" >> "$project/verdicts.json"
			      printf '"trial-%%s/unrelated-request":' "$trial_number" >> "$project/verdicts.json"
			      printf '{"status":"%%s","assertions":' "$verdict" >> "$project/verdicts.json"
			      printf '{"scope":{"status":"%%s","evidence":"fixture verdict"}}},' "$verdict" >> "$project/verdicts.json"
			      printf '"trial-%%s/edge-condition":' "$trial_number" >> "$project/verdicts.json"
			      printf '{"status":"%%s","assertions":' "$verdict" >> "$project/verdicts.json"
			      printf '{"edge-result":{"status":"%%s",' "$verdict" >> "$project/verdicts.json"
			      printf '"evidence":"fixture verdict"}}}}' >> "$project/verdicts.json"
			      printf '{"retained_project":"%%s","raw_result":"%%s"}' "$project" "$output" > "$receipt"
			    fi ;;
			esac
			""".formatted(cache, executorFailure, formalVerdict));
		requireThat(runner.toFile().setExecutable(true), "runnerExecutable").isTrue();
		return runner;
	}

	/**
	 * Writes the build plan that produces the runner artifact used by the failing-formal fixture.
	 *
	 * @param root the fixture root
	 * @return the build-plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeFailingFormalBuildPlan(Path root) throws IOException
	{
		Path build = write(root, "build-formal-fixture.sh", """
			#!/bin/sh
			mkdir -p runner-artifact/client runner-artifact/rules/common runner-artifact/rules/include/common
			printf artifact > runner-artifact/marker.txt
			printf 'CAT runner runtime manifest v2\\n' > runner-artifact/client/runner-runtime.manifest
			printf 'Read `rule.md` before task work.' > runner-artifact/rules/common/default-rules.md
			printf target > runner-artifact/rules/common/rule.md
			printf reuse > runner-artifact/rules/include/common/prefer-reuse.md
			printf preservation > runner-artifact/rules/include/common/preserve-required-distinctions.md
			""");
		return write(root, "formal-build-plan.json", """
			{
			  "working_directory": ".",
			  "command": ["/bin/sh", "%s"],
			  "inputs": ["build-formal-fixture.sh"],
			  "runner_artifact": "runner-artifact"
			}
			""".formatted(build));
	}

	/**
	 * Writes and verifies the lifecycle receipt that authorizes one fixture runner artifact for formal evaluation.
	 *
	 * @param temporary the fixture CAT work temporary root
	 * @param buildPlan the fixture runner build plan
	 * @param runner the fixture harness runner
	 * @throws IOException if the fixture artifact cannot complete the lifecycle check
	 */
	static void writeLifecycleSoakPrecondition(Path temporary, Path buildPlan, Path runner) throws IOException
	{
		ObjectNode assembly = PromptEvaluation.assemble(temporary, buildPlan, runner);
		Path artifact = Path.of(assembly.path("runner_artifact").asString());
		String fingerprint = RunnerRuntimeManifest.packagedFingerprint(artifact);
		Path workRoot = temporary.toAbsolutePath().normalize().getParent();
		if (workRoot == null)
			throw new IOException("Test lifecycle temporary directory has no work root: " + temporary);
		Path receipt = workRoot.resolve("prompt-evaluation-lifecycle-soaks").resolve(fingerprint + ".json");
		Files.createDirectories(receipt.getParent());
		try (ToolScope scope = new ToolScope())
		{
			Files.writeString(receipt,
				scope.json().writeValueAsString(RuntimeVerification.passedReceipt(scope.json(), fingerprint)));
		}
		RuntimeVerification.verifyLifecycleSoak(temporary, artifact);
	}

	/**
	 * Writes a fixture runtime manifest once, or verifies the existing one before another plan reuses its artifact.
	 *
	 * @param artifact the fixture artifact directory
	 * @throws IOException if its declared runtime is invalid
	 */
	static void ensureRunnerRuntimeManifest(Path artifact) throws IOException
	{
		if (Files.exists(artifact.resolve(RunnerRuntimeManifest.PATH)))
			RunnerRuntimeManifest.fingerprint(artifact);
		else
			RunnerRuntimeManifest.write(artifact);
	}

	/**
	 * Writes an identity-only plan whose runner and policy artifacts are copied into one invocation-local directory.
	 *
	 * @param root the isolated plan directory
	 * @return the plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeInvocationLocalIdentityPlan(Path root) throws IOException
	{
		return writeInvocationLocalIdentityPlan(root, "shared-writable-cache");
	}

	/**
	 * Writes an identity-only plan with the selected dependency-cache policy.
	 *
	 * @param root the isolated plan directory
	 * @param cachePolicyName the behaviorally relevant dependency-cache policy
	 * @return the plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeInvocationLocalIdentityPlan(Path root, String cachePolicyName) throws IOException
	{
		Path runnerArtifact = write(root, "runner-artifact/client/runner.txt", "same runner artifact\n");
		Path artifactRoot = runnerArtifact.getParent().getParent();
		write(artifactRoot, "rules/common/default-rules.md", "router guidance\n");
		write(artifactRoot, "rules/common/rule.md", "target rule guidance\n");
		write(artifactRoot, "rules/include/common/prefer-reuse.md", "reuse guidance\n");
		write(artifactRoot, "rules/include/common/preserve-required-distinctions.md", "preservation guidance\n");
		write(artifactRoot, "client/bin/fixture-runner", "#!/bin/sh\nexit 0\n");
		ensureRunnerRuntimeManifest(artifactRoot);
		Path sandboxPolicy = write(root, "sandbox-policy.json", "{\"policy\":\"runner-managed-isolation\"}\n");
		Path networkPolicy = write(root, "network-policy.json", "{\"policy\":\"runner-inherits-parent-network-policy\"}\n");
		Path cachePolicy = write(root, "cache-policy.json", "{\"policy\":\"%s\"}\n".formatted(cachePolicyName));
		return write(root, "plan.json", """
			{"identity":{"runner_launcher":"%1$s/client/bin/fixture-runner","runner_artifact":"%1$s",
			"executor_owner":"default","executor_model":"model-a",
			"executor_effort":"medium","executor_profile":"executor-profile","validator_owner":"default",
			"validator_model":"model-b","validator_effort":"medium","validator_profile":"validator-profile",
			"sandbox_policy":"%2$s","network_policy":"%3$s","cache_policy":"%4$s"},
			"executor":{"install_cat":true},
			"validator":{"install_cat":false},
			"validator_batch":{"install_cat":false,"read_only_evidence":false}}
			""".formatted(artifactRoot, sandboxPolicy, networkPolicy, cachePolicy));
	}

	/**
	 * Starts and completes one fixture regression check through the public CLI boundary.
	 *
	 * @param root the fixture directory
	 * @param suite the durable testcase suite
	 * @param prompt the candidate prompt artifact
	 * @param context the validator contract
	 * @param run the initially absent evaluation record
	 * @param plan the fixture execution plan
	 * @throws IOException if the check cannot retain its result
	 */
	static void beginScreen(Path root, Path suite, Path prompt, Path context, Path run, Path plan) throws IOException
	{
		PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
			context.toString(), run.toString()});
		Path workspace = root.resolve(run.getFileName() + "-workspace");
		Files.createDirectories(workspace);
		Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
			System.lineSeparator());
		PromptEvaluation.run(new String[]{"run-regression-check", run.toString(), workspace.toString(),
			plan.toString(), "--max-concurrency", "3"});
	}

	/**
	 * Starts and completes one fixture regression check with the supplied typed evaluator runner.
	 *
	 * @param root the fixture directory
	 * @param suite the durable testcase suite
	 * @param prompt the candidate prompt artifact
	 * @param context the validator contract
	 * @param run the initially absent evaluation record
	 * @param plan the fixture execution plan
	 * @param runner the runner that retains evaluator results
	 * @throws IOException if the check cannot retain its result
	 */
	static void beginScreen(Path root, Path suite, Path prompt, Path context, Path run, Path plan,
		HarnessEvaluationRunner runner) throws IOException
	{
		PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
			context.toString(), run.toString()});
		Path workspace = root.resolve(run.getFileName() + "-workspace");
		Files.createDirectories(workspace);
		Files.writeString(workspace.resolve(".cat-prompt-evaluation-evidence"), run.toAbsolutePath().normalize() +
			System.lineSeparator());
		PromptEvaluation.runRegressionCheck(run, workspace, plan, 3, runner);
	}

	/**
	 * Waits for a test fixture operation to publish a marker without relying on an arbitrary delay.
	 *
	 * @param file the marker to await
	 * @throws IOException if fixture I/O fails or the marker is not published in time
	 * @throws InterruptedException if the test thread is interrupted
	 */
	static void awaitFile(Path file) throws IOException, InterruptedException
	{
		if (Files.exists(file))
			return;
		try (java.nio.file.WatchService watcher = file.getFileSystem().newWatchService())
		{
			file.getParent().register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
			if (Files.exists(file))
				return;
			while (!Files.exists(file))
			{
				java.nio.file.WatchKey key = watcher.poll(10, java.util.concurrent.TimeUnit.SECONDS);
				if (key == null)
					throw new IOException("Timed out waiting for fixture marker: " + file);
				key.reset();
			}
		}
	}

	/**
	 * Runs a test-owned evaluator command and returns its UTF-8 output.
	 *
	 * @param command the command to run
	 * @return its UTF-8 output
	 * @throws IOException if command I/O fails
	 */
	static String output(IoCommand command) throws IOException
	{
		return command.run();
	}

	/**
	 * Records one testcase result, claiming the fixture's first whole-prompt trial when necessary.
	 *
	 * @param run the result record
	 * @param caseId the testcase identifier
	 * @param trial the whole-prompt trial number
	 * @param status the outcome status
	 * @throws IOException if recording fails
	 */
	static void record(Path run, String caseId, int trial, String status) throws IOException
	{
		if (PromptEvaluation.read(run).path("next_trial").asInt() == 1)
			output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "100"}));
		Path root = run.getParent();
		Path rawResult = write(root, caseId + "-" + trial + "-raw.json", "raw result\n");
		Path validatorResult = write(root, caseId + "-" + trial + "-validator.json", "validator result\n");
		PromptEvaluation.run(new String[]{"record", run.toString(), caseId, Integer.toString(trial), status,
			rawResult.toString(), validatorResult.toString()});
	}

	/**
	 * Writes one retained organic failure that does not yet identify whether routing or application caused it.
	 *
	 * @param root the fixture root
	 * @param suite the immutable testcase suite
	 * @param caseId the failed testcase
	 * @return the retained evaluation record
	 * @throws IOException if fixture I/O fails
	 */
	static Path unresolvedFailure(Path root, Path suite, String caseId) throws IOException
	{
		return recordedFailure(root, suite, caseId, null, "failed");
	}

	/**
	 * Writes one retained organic failure with established ownership.
	 *
	 * @param root the fixture root
	 * @param suite the immutable testcase suite
	 * @param caseId the failed testcase
	 * @param failureClass the established owner classification
	 * @return the retained evaluation record
	 * @throws IOException if fixture I/O fails
	 */
	static Path classifiedFailure(Path root, Path suite, String caseId, String failureClass) throws IOException
	{
		return recordedFailure(root, suite, caseId, failureClass, "failed");
	}

	/**
	 * Writes one retained organic failure with a terminal status and optional established ownership.
	 *
	 * @param root the fixture root
	 * @param suite the immutable testcase suite
	 * @param caseId the failed testcase
	 * @param failureClass the established classification, or {@code null} when unresolved
	 * @param status the retained terminal testcase status
	 * @return the retained evaluation record
	 * @throws IOException if fixture I/O fails
	 */
	static Path recordedFailure(Path root, Path suite, String caseId, String failureClass, String status)
		throws IOException
	{
		Path prompt = write(root, "diagnostic-prompt.md", "Prompt artifact\n");
		Path context = write(root, "diagnostic-context.md", "Validator contract\n");
		Path run = root.resolve("diagnostic-source-" + caseId + ".json");
		PromptEvaluation.run(new String[]{"begin", suite.toString(), prompt.toString(), "codex", "model-a",
			context.toString(), run.toString()});
		output(() -> PromptEvaluation.run(new String[]{"claim-trials", run.toString(), "1"}));
		Path raw = write(root, "diagnostic-raw-" + caseId + ".txt", "raw result\n");
		String contents = "{}\n";
		if (failureClass != null)
			contents = "{\"failure_class\":\"" + failureClass + "\"}\n";
		Path evidence = write(root, "diagnostic-evidence-" + caseId + ".json", contents);
		PromptEvaluation.run(new String[]{"record", run.toString(), caseId, "1", status, raw.toString(),
			evidence.toString()});
		return run;
	}

	/**
	 * Runs one I/O operation.
	 */
	@FunctionalInterface
	interface IoCommand
	{
		/**
		 * Runs the operation.
		 *
		 * @return caller-visible operation output
		 * @throws IOException if execution fails
		 */
		String run() throws IOException;
	}
}
