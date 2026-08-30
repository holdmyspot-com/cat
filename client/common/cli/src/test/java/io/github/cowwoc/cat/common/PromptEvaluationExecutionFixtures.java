package io.github.cowwoc.cat.common;

import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.ensureRunnerRuntimeManifest;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.nativeAgentMessage;
import static io.github.cowwoc.cat.common.PromptEvaluationFixtures.write;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes executor and validator plans consumed by {@link PromptEvaluation} execution scenarios.
 */
final class PromptEvaluationExecutionFixtures
{
	/**
	 * Prevents construction of fixture utilities.
	 */
	private PromptEvaluationExecutionFixtures()
	{
	}

	/**
	 * Adds current-format evaluator metadata to the requested-change scenario.
	 *
	 * @param suite the scenario-suite root
	 * @param metadata the JSON metadata object to merge
	 * @throws IOException if the fixture cannot be updated
	 */
	static void configureRequestedScenario(Path suite, String metadata) throws IOException
	{
		Path manifest = suite.resolve("suite.json");
		ObjectNode suiteMetadata = PromptEvaluation.object(PromptEvaluation.read(manifest), "suite metadata");
		ObjectNode scenarios = PromptEvaluation.object(suiteMetadata.path("scenarios"), "scenario metadata");
		ObjectNode requested = scenarios.withObject("requested-change");
		ObjectNode additions = PromptEvaluation.object(PromptEvaluation.parseJson(metadata), "scenario additions");
		for (Map.Entry<String, JsonNode> entry : additions.properties())
			requested.set(entry.getKey(), entry.getValue());
		PromptEvaluation.write(manifest, suiteMetadata);
	}

	/**
	 * Groups the artifact and policy files that identify one fixture execution plan.
	 *
	 * @param artifactRoot the runner artifact root
	 * @param sandboxPolicy the selected sandbox policy
	 * @param networkPolicy the selected network policy
	 * @param cachePolicy the selected cache policy
	 */
	private record PlanArtifacts(Path artifactRoot, Path sandboxPolicy, Path networkPolicy, Path cachePolicy)
	{
	}

	/**
	 * Writes a deterministic executor and batched-validator plan with passing verdicts.
	 *
	 * @param root the fixture directory
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeExecutionPlan(Path root) throws IOException
	{
		return writeExecutionPlan(root, "passed", true, false);
	}

	/**
	 * Writes an execution plan for a test that supplies its own typed runner.
	 *
	 * @param root the fixture directory
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeStaticExecutionPlan(Path root) throws IOException
	{
		return writeStaticExecutionPlan(root, false);
	}

	/**
	 * Writes an execution plan for a test-supplied runner with the selected batch-validator evidence contract.
	 *
	 * @param root the fixture directory
	 * @param directEvidence whether the batch validator reads immutable executor evidence directly
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeStaticExecutionPlan(Path root, boolean directEvidence) throws IOException
	{
		PlanArtifacts artifacts = writePlanArtifacts(root);
		Path staticRunner = write(root, "static-runner", "Supplied by the test.\n");
		String validatorCapability = "";
		if (directEvidence)
			validatorCapability = ",\"read_only_evidence\":true";
		return writeTypedPlan(root, staticRunner, artifacts, validatorCapability,
			",\n  \"validator_batch\":{\"install_cat\":false,\"read_only_evidence\":" + directEvidence + "}");
	}

	/**
	 * Retains one deterministic evaluator result in the request-owned project for a static runner test.
	 *
	 * @param requests the observed typed requests
	 * @param request the evaluator request to retain
	 * @param validatorStatus the verdict status for every validator assertion
	 * @return the retained evaluator result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	static HarnessEvaluationExecution staticEvaluationResult(List<HarnessEvaluationRequest> requests,
		HarnessEvaluationRequest request, String validatorStatus) throws IOException
	{
		return staticEvaluationResult(requests, request, validatorStatus, false);
	}

	/**
	 * Retains one deterministic evaluator result and, when needed, a native final response for a single-validator test.
	 *
	 * @param requests the observed typed requests
	 * @param request the evaluator request to retain
	 * @param validatorStatus the verdict status for every validator assertion
	 * @param retainFinalResponse whether executor evidence includes a completed reader response
	 * @return the retained evaluator result
	 * @throws IOException if the test-owned evidence cannot be written
	 */
	static HarnessEvaluationExecution staticEvaluationResult(List<HarnessEvaluationRequest> requests,
		HarnessEvaluationRequest request, String validatorStatus, boolean retainFinalResponse) throws IOException
	{
		requests.add(request);
		Path retainedProject = request.project().resolve("retained");
		Files.createDirectories(retainedProject);
		if (request.installCat())
		{
			Files.copy(request.project().resolve("project.txt"), retainedProject.resolve("implementation.txt"));
			String transcript = "static result\n";
			if (retainFinalResponse)
				transcript = nativeAgentMessage("Static final reader response");
			Files.writeString(request.transcript(), transcript);
		}
		else
		{
			Path verdictsPath = request.project().resolve("verdicts.json");
			if (Files.isRegularFile(verdictsPath))
			{
				ObjectNode verdicts = PromptEvaluation.object(PromptEvaluation.read(verdictsPath),
					"validator verdict skeleton");
				for (Map.Entry<String, JsonNode> entry : verdicts.properties())
				{
					ObjectNode verdict = PromptEvaluation.object(entry.getValue(), "validator verdict");
					verdict.put("status", validatorStatus);
					ObjectNode assertions = PromptEvaluation.object(verdict.path("assertions"), "assertions");
					for (Map.Entry<String, JsonNode> assertionEntry : assertions.properties())
					{
						ObjectNode assertion = PromptEvaluation.object(assertionEntry.getValue(), "validator assertion");
						assertion.put("status", validatorStatus);
						assertion.put("evidence", "static fixture evidence");
					}
				}
				PromptEvaluation.write(retainedProject.resolve("verdicts.json"), verdicts);
			}
			Files.writeString(retainedProject.resolve("verdict.txt"), validatorStatus);
			Files.writeString(request.transcript(), "static validator result\n");
		}
		HarnessEvaluationResult result = HarnessEvaluationResult.retained(retainedProject, request.transcript());
		return new HarnessEvaluationExecution(result, 1, 2);
	}

	/**
	 * Writes a single-validator fixture plan whose executor retains a final reader response.
	 *
	 * @param root the fixture directory
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeExecutionPlanWithFinalResponse(Path root) throws IOException
	{
		Path plan = writeExecutionPlan(root, "passed", false, false);
		String response = nativeAgentMessage("Final reader response");
		Path launcher = root.resolve("fixture-runner");
		Files.writeString(launcher, """
			#!/bin/sh
			while [ "$#" -gt 0 ]; do
			  case "$1" in
			    --project) project="$2"; shift 2 ;;
			    --output) output="$2"; shift 2 ;;
			    --receipt) receipt="$2"; shift 2 ;;
			    --install-cat) install_cat=true; shift ;;
			    *) shift ;;
			  esac
			done
			if [ "${install_cat:-false}" != true ]; then exit 75; fi
			cat > "$project/bridge-input.jsonl"
			mkdir -p "$project/retained"
			cp "$project/project.txt" "$project/retained/implementation.txt"
			printf '%%s\\n' '%s' > "$output"
			printf '{"retained_project":"%%s","raw_result":"%%s"}' "$project/retained" "$output" > "$receipt"
			""".formatted(response.replace("'", "'\\\"'\\\"'")));
		requireThat(launcher.toFile().setExecutable(true), "fixtureRunnerExecutable").isTrue();
		return plan;
	}

	/**
	 * Writes a deterministic executor and batched-validator plan with the selected verdict.
	 *
	 * @param root the fixture directory
	 * @param verdict the validator verdict
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeExecutionPlan(Path root, String verdict) throws IOException
	{
		return writeExecutionPlan(root, verdict, true, false);
	}

	/**
	 * Writes a deterministic executor and validator plan with the selected validator protocol.
	 *
	 * @param root the fixture directory
	 * @param verdict the validator verdict
	 * @param batchValidator whether to include the batched validator protocol
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeExecutionPlan(Path root, String verdict, boolean batchValidator) throws IOException
	{
		return writeExecutionPlan(root, verdict, batchValidator, false);
	}

	/**
	 * Writes a deterministic executor and validator plan with the selected batch and evidence contracts.
	 *
	 * @param root the fixture directory
	 * @param verdict the validator verdict
	 * @param batchValidator whether to include the batched validator protocol
	 * @param directEvidence whether the single validator reads retained evidence without a copy
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeExecutionPlan(Path root, String verdict, boolean batchValidator, boolean directEvidence)
		throws IOException
	{
		return writeExecutionPlan(root, verdict, batchValidator, directEvidence, true);
	}

	/**
	 * Writes a deterministic executor and validator plan with the selected assertion-evidence contract.
	 *
	 * @param root the fixture directory
	 * @param verdict the validator verdict
	 * @param batchValidator whether to include the batched validator protocol
	 * @param directEvidence whether the single validator reads retained evidence without a copy
	 * @param includeAssertionEvidence whether batch verdicts include required assertion evidence
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeExecutionPlan(Path root, String verdict, boolean batchValidator, boolean directEvidence,
		boolean includeAssertionEvidence) throws IOException
	{
		return writeExecutionPlan(root, verdict, batchValidator, directEvidence, includeAssertionEvidence, "", "");
	}

	/**
	 * Writes a fixture plan whose executor records when each testcase begins.
	 *
	 * @param root the fixture directory
	 * @param starts the executor-start log
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeInterleavingExecutionPlan(Path root, Path starts) throws IOException
	{
		String escapedStarts = starts.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		String preamble = "printf '%s\\n' \"$(basename \"$(dirname \"$project\")\")\" >> \"" +
			escapedStarts + "\"; sleep 1; ";
		return writeExecutionPlan(root, "passed", true, false, true, preamble, "");
	}

	/**
	 * Writes a fixture plan whose independent validator records each group start and remains active long enough to prove
	 * concurrent scheduling.
	 *
	 * @param root the fixture directory
	 * @param starts the validator-start log
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeValidatorGroupingExecutionPlan(Path root, Path starts) throws IOException
	{
		String escapedStarts = starts.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		String preamble = "sed -nE 's|^[[:space:]]*\"id\" : \"(trial-[0-9]+/[a-z-]+)\".*|\\1|p' " +
			"\"$project/validator-prompt.md\" | " +
			"head -1 >> \"" + escapedStarts + "\"; sleep 1; ";
		return writeExecutionPlan(root, "passed", true, false, true, "", preamble);
	}

	/**
	 * Writes a deterministic executor and validator plan with selected tracing commands.
	 *
	 * @param root the fixture directory
	 * @param verdict the validator verdict
	 * @param batchValidator whether to include the batched validator protocol
	 * @param directEvidence whether the single validator reads retained evidence without a copy
	 * @param includeAssertionEvidence whether batch verdicts include required assertion evidence
	 * @param executorPreamble shell code that runs before the fixture executor
	 * @param validatorPreamble shell code that runs before the fixture batch validator
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	static Path writeExecutionPlan(Path root, String verdict, boolean batchValidator, boolean directEvidence,
		boolean includeAssertionEvidence, String executorPreamble, String validatorPreamble) throws IOException
	{
		PlanArtifacts artifacts = writePlanArtifacts(root);
		String executor = executorPreamble + "cp \"$project/project.txt\" \"$project/retained/implementation.txt\"; " +
			"printf raw > \"$output\"; printf '{\"retained_project\":\"%s\",\"raw_result\":\"%s\"}' " +
			"\"$project/retained\" \"$output\" > \"$receipt\"";
		String validator = "if [ -f \"$project/implementation.txt\" ]; then cp \"$project/implementation.txt\" " +
			"\"$project/retained/implementation.txt\"; fi; printf validator > \"$output\"; printf " + verdict +
			" > \"$project/retained/verdict.txt\"; printf '{\"retained_project\":\"%s\",\"raw_result\":\"%s\"}' " +
			"\"$project/retained\" \"$output\" > \"$receipt\"";
		String validatorCapability = "";
		if (directEvidence)
			validatorCapability = ",\"read_only_evidence\":true";
		String batchValidatorPlan = "";
		String batchCommand = "exit 44";
		if (batchValidator)
		{
			boolean omitSecondTrial = verdict.equals("missing-second");
			boolean edgeConditionFailure = verdict.equals("edge-condition-failed");
			boolean stressFailure = verdict.equals("stress-failed");
			String effectiveVerdict = verdict;
			if (omitSecondTrial || edgeConditionFailure || stressFailure)
				effectiveVerdict = "passed";
			String edgeConditionVerdict = effectiveVerdict;
			if (edgeConditionFailure)
				edgeConditionVerdict = "failed";
			String evidence = "";
			if (includeAssertionEvidence)
				evidence = ",\"evidence\":\"fixture evidence\"";
			String stressFailureCheck = "";
			if (stressFailure)
				stressFailureCheck = "case \"$key\" in trial-2/requested-change) current_verdict=failed ;; esac; ";
			batchCommand = validatorPreamble + "printf validator > \"$output\"; printf '{' > " +
				"\"$project/retained/verdicts.json\"; first=true; " +
				"for key in $(sed -nE 's|^[[:space:]]*\"id\" : \"(trial-[0-9]+/[a-z-]+)\".*|\\1|p' " +
				"\"$project/validator-prompt.md\" | " +
					"sort -u); do case \"$key\" in " +
					"*/requested-change) assertion=result; current_verdict=" + effectiveVerdict + " ;; " +
					"*/unrelated-request) assertion=scope; current_verdict=" + effectiveVerdict + " ;; " +
					"*/edge-condition) assertion=edge-result; current_verdict=" + edgeConditionVerdict + " ;; " +
					"*) exit 43 ;; esac; " + stressFailureCheck + "case \"$key\" in trial-2/*) if [ \"" +
						omitSecondTrial + "\" = true ]; " +
				"then continue; fi ;; esac; if [ \"$first\" = false ]; then printf ',' >> " +
				"\"$project/retained/verdicts.json\"; fi; " +
				"first=false; printf '\"%s\":{\"status\":\"%s\",\"assertions\":{\"%s\":{\"status\":\"%s\"" +
				evidence + "}}}' \"$key\" \"$current_verdict\" \"$assertion\" \"$current_verdict\" " +
					">> \"$project/retained/verdicts.json\"; done; " +
				"printf '}' >> \"$project/retained/verdicts.json\"; " +
				"printf '{\"retained_project\":\"%s\",\"raw_result\":\"%s\"}' \"$project/retained\" " +
				"\"$output\" > \"$receipt\"";
			String semanticControlVerdicts =
				"'{\"semantic-control/requested-change/result-missing\":{\"status\":\"failed\", " +
				"\"assertions\":{\"result\":{\"status\":\"failed\",\"evidence\":\"controlled missing result\"}}}}'";
			String semanticControlBatch = "printf validator > \"$output\"; printf " + semanticControlVerdicts + " " +
				"> \"$project/retained/verdicts.json\"; printf '{\"retained_project\":\"%s\",\"raw_result\":\"%s\"}' " +
				"\"$project/retained\" \"$output\" > \"$receipt\"";
			batchCommand = "if grep -q semantic-control \"$project/validator-prompt.md\"; then " +
				semanticControlBatch + "; else " + batchCommand + "; fi";
			String batchCapability = ",\"read_only_evidence\":false";
			if (directEvidence)
				batchCapability = ",\"read_only_evidence\":true";
			batchValidatorPlan = ",\n  \"validator_batch\":{\"install_cat\":false" + batchCapability + "}";
		}
		Path launcher = write(root, "fixture-runner", """
			#!/bin/sh
			while [ "$#" -gt 0 ]; do
			  case "$1" in
			    --project) project="$2"; shift 2 ;;
			    --output) output="$2"; shift 2 ;;
			    --receipt) receipt="$2"; shift 2 ;;
			    --install-cat) install_cat=true; shift ;;
			    --read-only-evidence) [ -d "$2" ] || exit 42; shift 2 ;;
			    *) shift ;;
			  esac
			done
			cat > "$project/bridge-input.jsonl"
			mkdir -p "$project/retained"
			if [ "${install_cat:-false}" = true ]; then
			  %s
			elif [ -f "$project/verdicts.json" ]; then
			  %s
			else
			  %s
			fi
			""".formatted(executor, batchCommand, validator));
		requireThat(launcher.toFile().setExecutable(true), "fixtureRunnerExecutable").isTrue();
		return writeTypedPlan(root, launcher, artifacts, validatorCapability, batchValidatorPlan);
	}

	/**
	 * Writes the stable artifact and policy inputs for one fixture execution plan.
	 *
	 * @param root the fixture directory
	 * @return the generated plan artifacts
	 * @throws IOException if fixture I/O fails
	 */
	private static PlanArtifacts writePlanArtifacts(Path root) throws IOException
	{
		Path runnerArtifact = write(root, "runner-artifact/client/runner.txt", "runner artifact\n");
		Path artifactRoot = runnerArtifact.getParent().getParent();
		write(artifactRoot, "rules/common/default-rules.md", "router guidance\n");
		write(artifactRoot, "rules/common/rule.md", "target rule guidance\n");
		write(artifactRoot, "rules/include/common/prefer-reuse.md", "reuse guidance\n");
		write(artifactRoot, "rules/include/common/preserve-required-distinctions.md", "preservation guidance\n");
		ensureRunnerRuntimeManifest(artifactRoot);
		Path sandboxPolicy = write(root, "sandbox-policy.json", "sandbox policy\n");
		Path networkPolicy = write(root, "network-policy.json", "network policy\n");
		Path cachePolicy = write(root, "cache-policy.json", "cache policy\n");
		return new PlanArtifacts(artifactRoot, sandboxPolicy, networkPolicy, cachePolicy);
	}

	/**
	 * Writes one typed executor and validator plan that uses the supplied bridge launcher.
	 *
	 * @param root the fixture directory
	 * @param launcher the bridge launcher declared by the plan
	 * @param artifacts the artifact and policy inputs declared by the plan
	 * @param validatorCapability the optional single-validator capability field
	 * @param batchValidator the optional batch-validator declaration
	 * @return the execution plan path
	 * @throws IOException if fixture I/O fails
	 */
	private static Path writeTypedPlan(Path root, Path launcher, PlanArtifacts artifacts, String validatorCapability,
		String batchValidator) throws IOException
	{
		return write(root, "plan.json", """
			{
			  "identity": {
			    "runner_launcher":"%s",
			    "runner_artifact":"%s",
			    "executor_owner":"default",
			    "executor_model":"model-a",
			    "executor_effort":"medium",
			    "executor_profile":"executor-profile",
			    "validator_owner":"default",
			    "validator_model":"model-b",
			    "validator_effort":"medium",
			    "validator_profile":"validator-profile",
			    "sandbox_policy":"%s",
			    "network_policy":"%s",
			    "cache_policy":"%s"
			  },
			  "validator_excluded_directories":[".git","node_modules"],
			  "executor":{"install_cat":true},
			  "validator":{"install_cat":false%s}%s
			}
			""".formatted(launcher, artifacts.artifactRoot(), artifacts.sandboxPolicy(), artifacts.networkPolicy(),
				artifacts.cachePolicy(), validatorCapability, batchValidator));
	}
}
