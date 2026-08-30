package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.evaluation.PublicCommands;
import io.github.cowwoc.cat.common.evaluation.RunIntegrity;
import io.github.cowwoc.cat.common.evaluation.RoutingEvidence;
import io.github.cowwoc.cat.common.evaluation.SuitePurpose;
import io.github.cowwoc.cat.common.evaluation.SuiteContract;
import io.github.cowwoc.cat.common.evaluation.Statistics;
import io.github.cowwoc.cat.common.evaluation.Timing;
import io.github.cowwoc.cat.common.evaluation.Tombstone;
import io.github.cowwoc.cat.common.evaluation.ValidatorLifecycle;
import io.github.cowwoc.cat.common.evaluation.ValidatorPrompt;
import io.github.cowwoc.cat.common.evaluation.AttemptRecorder;
import io.github.cowwoc.cat.common.evaluation.AttemptLedger;
import io.github.cowwoc.cat.common.evaluation.AttemptStatus;
import io.github.cowwoc.cat.common.evaluation.Assembly;
import io.github.cowwoc.cat.common.evaluation.ArtifactRetention;
import io.github.cowwoc.cat.common.evaluation.AssemblyCache;
import io.github.cowwoc.cat.common.evaluation.Benchmark;
import io.github.cowwoc.cat.common.evaluation.BenchmarkExecution;
import io.github.cowwoc.cat.common.evaluation.BenchmarkRecords;
import io.github.cowwoc.cat.common.evaluation.BuildInputs;
import io.github.cowwoc.cat.common.evaluation.Calibration;
import io.github.cowwoc.cat.common.evaluation.CapabilityDiagnostic;
import io.github.cowwoc.cat.common.evaluation.CandidateBundle;
import io.github.cowwoc.cat.common.evaluation.CandidateController;
import io.github.cowwoc.cat.common.evaluation.CandidateLifecycle;
import io.github.cowwoc.cat.common.evaluation.CandidateReviews;
import io.github.cowwoc.cat.common.evaluation.CandidateState;
import io.github.cowwoc.cat.common.evaluation.Certification;
import io.github.cowwoc.cat.common.evaluation.Comparison;
import io.github.cowwoc.cat.common.evaluation.CorrectionJournal;
import io.github.cowwoc.cat.common.evaluation.DiagnosticKind;
import io.github.cowwoc.cat.common.evaluation.Decision;
import io.github.cowwoc.cat.common.evaluation.DeterministicChecks;
import io.github.cowwoc.cat.common.evaluation.Diagnostics;
import io.github.cowwoc.cat.common.evaluation.EvaluationKind;
import io.github.cowwoc.cat.common.evaluation.EvaluationWorkspaceLifecycle;
import io.github.cowwoc.cat.common.evaluation.EvidenceFinalization;
import io.github.cowwoc.cat.common.evaluation.EvidenceHandoff;
import io.github.cowwoc.cat.common.evaluation.Lifecycle;
import io.github.cowwoc.cat.common.evaluation.Recovery;
import io.github.cowwoc.cat.common.evaluation.ExecutorEvidence;
import io.github.cowwoc.cat.common.evaluation.Execution;
import io.github.cowwoc.cat.common.evaluation.ExecutionPlan;
import io.github.cowwoc.cat.common.evaluation.EvaluationFiles;
import io.github.cowwoc.cat.common.evaluation.PromptInput;
import io.github.cowwoc.cat.common.evaluation.PromptEvaluationOperation;
import io.github.cowwoc.cat.common.evaluation.TrialClaims;
import io.github.cowwoc.cat.common.evaluation.TrialPreparation;
import io.github.cowwoc.cat.common.evaluation.ExecutionScheduler;
import io.github.cowwoc.cat.common.evaluation.Executor;
import io.github.cowwoc.cat.common.evaluation.ValidatorExecution;
import io.github.cowwoc.cat.common.evaluation.WorkerTiming;
import io.github.cowwoc.cat.common.evaluation.FailureClass;
import io.github.cowwoc.cat.common.evaluation.FailureClassifier;
import io.github.cowwoc.cat.common.evaluation.Generalization;
import io.github.cowwoc.cat.common.evaluation.GoalStatus;
import io.github.cowwoc.cat.common.evaluation.Identity;
import io.github.cowwoc.cat.common.evaluation.Lock;
import io.github.cowwoc.cat.common.evaluation.LifecycleSoak;
import io.github.cowwoc.cat.common.evaluation.Outcomes;
import io.github.cowwoc.cat.common.evaluation.Preparation;
import io.github.cowwoc.cat.common.evaluation.Preflight;
import io.github.cowwoc.cat.common.evaluation.Profile;
import io.github.cowwoc.cat.common.evaluation.RecordStore;
import io.github.cowwoc.cat.common.evaluation.RecurrenceReview;
import io.github.cowwoc.cat.common.evaluation.RegressionCheck;
import io.github.cowwoc.cat.common.evaluation.Records;
import io.github.cowwoc.cat.common.evaluation.Report;
import io.github.cowwoc.cat.common.evaluation.RevisionReview;
import io.github.cowwoc.cat.common.evaluation.RuntimeRevalidation;
import io.github.cowwoc.cat.common.evaluation.RuntimeVerification;
import io.github.cowwoc.cat.common.evaluation.RouterPromotion;
import io.github.cowwoc.cat.common.evaluation.RoutingMatrix;
import io.github.cowwoc.cat.common.evaluation.ValidatorEvidence;
import io.github.cowwoc.cat.common.runner.HarnessConcurrencyLimit;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRunner;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessRunReceipt;
import io.github.cowwoc.cat.common.runner.NativeHarnessEvaluationRunner;
import io.github.cowwoc.cat.common.secrets.EvaluatorSecrets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs prompt tests and records the evidence needed to compare and certify whole-prompt compliance.
 * <p>
 * Each run creates an evaluation record: a JSON document containing the tested suite, execution conditions, and final
 * evidence. A candidate-state record separately identifies the prompt revision that passed the latest regression
 * check and failures that a later revision must cover. The evaluator uses that record to select the next permitted
 * workflow step; it never chooses a prompt revision.
 * <p>
 * A recorded item is stored so a later invocation can inspect or resume it. A receipt is a recorded JSON result that
 * identifies the operation, its inputs, and its outcome. A candidate bundle is a fixed copy of the prompt files under
 * test. An organic test checks both rule routing and prompt application; routing-only and application-only diagnostics
 * separately locate a failure after an organic test fails. A deterministic check obtains its result from project code;
 * a semantic check asks the independent reviewer to interpret the supplied evidence. A promotion marks one candidate
 * as eligible for certification. A terminal result is final: it either records the requested outcome or explains why
 * no further work may begin.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent commands preserve every recorded evaluation result.
 */
public final class PromptEvaluation
{
	/**
	 * Creates diagnostics access for one operation mapper.
	 *
	 * @param json the operation's JSON mapper
	 * @return diagnostics access bound to that mapper
	 */
	private static Diagnostics.Access diagnostics(JsonMapper json)
	{
		return new Diagnostics.Access()
		{
			@Override
			public JsonMapper json()
			{
				return json;
			}

		@Override
		public ObjectNode readObject(Path source, String description) throws IOException
		{
			return object(read(source), description);
		}

		@Override
		public String suiteFingerprint(Path suite) throws IOException
		{
			return PromptEvaluation.suiteFingerprint(suite);
		}

		@Override
		public ObjectNode testcase(ObjectNode run, Path runPath, String caseId) throws IOException
		{
			return PromptEvaluation.testcase(run, runPath, caseId);
		}

		@Override
		public ObjectNode classifyFailure(Path runPath, String caseId, int trial) throws IOException
		{
			return PromptEvaluation.classifyFailure(runPath, caseId, trial);
		}

		@Override
		public String scenarioTask(String contents, Path scenario) throws IOException
		{
			return PromptEvaluation.scenarioTask(contents, scenario);
		}

		@Override
		public String routingInstruction(boolean expectsSelected, boolean permitsOptionalSelection,
			boolean expectsNotSelected)
		{
			return routingDiagnosticInstruction(expectsSelected, permitsOptionalSelection, expectsNotSelected);
		}

		@Override
		public void copyFixture(Path source, Path destination) throws IOException
		{
			EvaluationFiles.copyFixture(source, destination);
		}

		@Override
		public void write(Path destination, ObjectNode contents) throws IOException
		{
			PromptEvaluation.write(destination, contents);
		}

		@Override
		public void delete(Path root) throws IOException
		{
			TemporaryDirectories.deleteRecursively(root);
		}
		};
	}

	public static final int SCHEMA_VERSION = Records.schemaVersion();
	static final int MAXIMUM_SUITE_RUNS = 100;
	static final String CANDIDATE_CONTROLLER_LOCK_SUFFIX = ".controller.lock";
	static final String CANDIDATE_CONTROLLER_STATUS_SUFFIX = ".controller.json";
	public static final int MAXIMUM_PREFLIGHT_RECEIPTS = 16;
	private static final String DIRECT_VALIDATOR_EXECUTOR_OUTPUT = ".cat-evaluation-executor-result.txt";
	private static final String DIRECT_VALIDATOR_FINAL_RESPONSE = ".cat-evaluation-final-response.md";
	public static final String VALIDATOR_FINAL_RESPONSE = "executor-response.md";
	public static final String ATTEMPT_LEDGER_SUFFIX = ".attempts.jsonl";
	private static final Set<String> SUPPORTED_VALIDATOR_EXCLUDED_DIRECTORIES = Set.of(".git", ".gradle", ".m2",
		".venv", "build", "dist", "node_modules", "target", "vendor", "venv");
	private static final EvaluationWorkspaceLifecycle WORKSPACE_LIFECYCLE =
		new EvaluationWorkspaceLifecycle(new EvaluationWorkspaceLifecycle.Access()
		{
			@Override
			public void prepareEmptyWorkspace(Path workspaceRoot) throws IOException
			{
				EvaluationWorkspaceLifecycle.prepareTerminalWorkspace(workspaceRoot);
			}

			@Override
			public void recordActiveWorkspace(Path runPath, Path workspaceRoot) throws IOException
			{
				PromptEvaluation.recordActiveWorkspace(runPath, workspaceRoot);
			}

			@Override
			public void clearActiveWorkspace(Path runPath, Path workspaceRoot) throws IOException
			{
				PromptEvaluation.clearActiveWorkspace(runPath, workspaceRoot);
			}

			@Override
			public EvaluationWorkspaceLifecycle.Heartbeat startHeartbeat(Path workspaceRoot, Lock activeLock)
			{
				return Lifecycle.startHeartbeat(workspaceRoot, activeLock);
			}

			@Override
			public void publishActiveStatus(Path workspaceRoot, String phase) throws IOException
			{
				Lifecycle.publishActiveStatus(workspaceRoot, phase);
			}

			@Override
			public void publishTerminalStatus(Path workspaceRoot) throws IOException
			{
				Lifecycle.publishTerminalStatus(workspaceRoot);
			}

			@Override
			public void writeInterruption(Path runPath, String diagnostic) throws IOException
			{
				writeText(EvaluationWorkspaceLifecycle.interruptionMarker(runPath), diagnostic);
			}

			@Override
			public void interruptActiveProcesses()
			{
				ProcessExecution.interruptActive();
			}
		});
	/**
	 * Creates terminal-evidence services bound to one operation mapper.
	 *
	 * @param json the operation-owned JSON mapper
	 * @return the terminal-evidence service for that operation
	 */

	private static EvidenceFinalization evidenceFinalization(JsonMapper json)
	{
		return new EvidenceFinalization(new EvidenceFinalization.Operations()
		{
			@Override
			public ObjectNode status(Path runPath, Path workspaceRoot) throws IOException
			{
				return Lifecycle.status(json, runPath, workspaceRoot, true);
			}

			@Override
			public Path defaultDestination(Path workRoot, Path runPath) throws IOException
			{
				return defaultRetainedEvidenceDirectory(workRoot, runPath);
			}

			@Override
			public ObjectNode handoff(Path runPath, Path workspaceRoot, Path durableDirectory, Path temporaryRoot,
				Path workRoot) throws IOException
			{
				return PromptEvaluation.handoff(json, runPath, workspaceRoot, durableDirectory, temporaryRoot, workRoot);
			}

			@Override
			public Path retainedEvidenceRoot(ObjectNode handoff, Path runPath) throws IOException
			{
				return path(text(handoff, "evidence_root", runPath));
			}

			@Override
			public Path evidenceRoot(Path temporaryRoot, Path runPath) throws IOException
			{
				return ArtifactRetention.ownedEvidence(temporaryRoot, runPath);
			}

			@Override
			public long byteCount(Path root) throws IOException
			{
				return fileTreeBytes(root);
			}

			@Override
			public Path replaceCandidateReference(Path runPath, Path recordRoot) throws IOException
			{
				return Tombstone.replace(json, runPath, recordRoot);
			}

			@Override
			public void deleteDetachedWorkspace(Path temporaryRoot, Path workspaceRoot, Path recordRoot)
				throws IOException
			{
				if (!workspaceRoot.toAbsolutePath().normalize().startsWith(recordRoot))
					InvocationDirectory.delete(temporaryRoot, workspaceRoot);
			}

			@Override
			public void deleteRecord(Path recordRoot) throws IOException
			{
				TemporaryDirectories.deleteRecursively(recordRoot);
			}
		});
	}

	private static final Report.Access REPORT_ACCESS = new Report.Access()
	{
		@Override
		public ObjectNode readRun(Path runPath) throws IOException
		{
			return object(read(runPath), "run");
		}

		@Override
		public ObjectNode decide(Path runPath) throws IOException
		{
			return PromptEvaluation.decide(runPath);
		}

		@Override
		public String candidateIdentity(ObjectNode run, Path runPath) throws IOException
		{
			return PromptEvaluation.candidateIdentity(run, runPath);
		}

		@Override
		public ArrayNode attempts(ObjectNode run, Path runPath) throws IOException
		{
			return PromptEvaluation.attempts(run, runPath);
		}
	};
	private static final CandidateLifecycle.Access CANDIDATE_ACCESS = new CandidateLifecycle.Access()
	{
		@Override
		public void validateCandidateState(Path temporaryRoot, Path candidateStatePath) throws IOException
		{
			validateDurableCandidateStatePath(temporaryRoot, candidateStatePath);
		}

		@Override
		public void validateMaximumConcurrency(int maximumConcurrency) throws IOException
		{
			PromptEvaluation.validateMaximumConcurrency(maximumConcurrency);
		}
	};

	static
	{
		DeterministicChecks.configure(new DeterministicChecks.Access()
		{
			@Override
			public ObjectNode object(JsonNode value, String description) throws IOException
			{
				return PromptEvaluation.object(value, description);
			}

			@Override
			public ObjectNode readObject(Path source, String description) throws IOException
			{
				return object(read(source), description);
			}

			@Override
			public ArrayNode array(JsonNode value, String description, Path source) throws IOException
			{
				return PromptEvaluation.array(value, description, source);
			}

			@Override
			public String text(ObjectNode record, String field, Path source) throws IOException
			{
				return PromptEvaluation.text(record, field, source);
			}

			@Override
			public Path path(String value)
			{
				return PromptEvaluation.path(value);
			}

			@Override
			public void write(Path destination, ObjectNode receipt) throws IOException
			{
				PromptEvaluation.write(destination, receipt);
			}

			@Override
			public ObjectNode prepareWritableEvidence(Path retainedProject, Path rawResult, Path workspace)
				throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					return ValidatorEvidence.describe(scope.json(), retainedProject, rawResult, workspace,
						Set.of(".git", "node_modules"), VALIDATOR_FINAL_RESPONSE);
				}
			}

			@Override
			public void runProcess(List<String> command, Path workspace, Path output, Path diagnostic)
				throws IOException
			{
				ProcessExecution.runStructured(command, workspace, "", output, diagnostic);
			}
		});
		Identity.configure(new Identity.Access()
		{
			@Override
			public ObjectNode readObject(Path source, String description) throws IOException
			{
				return object(read(source), description);
			}

			@Override
			public String text(ObjectNode record, String field, Path source) throws IOException
			{
				return PromptEvaluation.text(record, field, source);
			}

			@Override
			public ObjectNode object(JsonNode value, String description) throws IOException
			{
				return PromptEvaluation.object(value, description);
			}

			@Override
			public ArrayNode array(JsonNode value, String description, Path source) throws IOException
			{
				return PromptEvaluation.array(value, description, source);
			}

			@Override
			public Path path(String value)
			{
				return PromptEvaluation.path(value);
			}

			@Override
			public String sha256(Path artifact) throws IOException
			{
				return PromptEvaluation.sha256(artifact);
			}

			@Override
			public String sha256Text(String value) throws IOException
			{
				return PromptEvaluation.sha256(value);
			}

			@Override
			public String fingerprintArtifact(Path artifact) throws IOException
			{
				return PromptEvaluation.fingerprintArtifact(artifact);
			}
		});
		Tombstone.configure(new Tombstone.Access()
		{
			@Override
			public ObjectNode readObject(Path source, String description) throws IOException
			{
				return object(read(source), description);
			}

			@Override
			public String text(ObjectNode record, String field, Path source) throws IOException
			{
				return PromptEvaluation.text(record, field, source);
			}

			@Override
			public ObjectNode object(JsonNode value, String description) throws IOException
			{
				return PromptEvaluation.object(value, description);
			}

			@Override
			public ArrayNode array(JsonNode value, String description, Path source) throws IOException
			{
				return PromptEvaluation.array(value, description, source);
			}

			@Override
			public String sha256(Path artifact) throws IOException
			{
				return PromptEvaluation.sha256(artifact);
			}

			@Override
			public String sha256Text(String value) throws IOException
			{
				return PromptEvaluation.sha256(value);
			}

			@Override
			public void write(Path destination, ObjectNode record) throws IOException
			{
				writeAtomically(destination, record);
			}

			@Override
			public void withLock(Path recordPath, Tombstone.Access.LockedOperation operation) throws IOException
			{
				withRunLock(recordPath, operation::execute);
			}

			@Override
			public void rewriteExactPath(JsonNode record, Path source, Path replacement)
			{
				EvidenceHandoff.rewriteExactPath(record, source, replacement);
			}
		});
		RecurrenceReview.configure(new RecurrenceReview.Access()
		{
			@Override
			public ObjectNode object(JsonNode value, String description) throws IOException
			{
				return PromptEvaluation.object(value, description);
			}

			@Override
			public ObjectNode readObject(Path source, String description) throws IOException
			{
				return object(read(source), description);
			}

			@Override
			public String text(ObjectNode record, String field, Path source) throws IOException
			{
				return PromptEvaluation.text(record, field, source);
			}

			@Override
			public ArrayNode array(JsonNode value, String name, Path source) throws IOException
			{
				return PromptEvaluation.array(value, name, source);
			}

			@Override
			public ArrayNode attempts(ObjectNode run, Path runPath) throws IOException
			{
				return PromptEvaluation.attempts(run, runPath);
			}

			@Override
			public Path requiredArtifact(String value, String description) throws IOException
			{
				return PromptEvaluation.requiredArtifact(value, description);
			}
		});
		CorrectionJournal.configure(new CorrectionJournal.Access()
		{
			@Override
			public ObjectNode read(Path journalPath, String description) throws IOException
			{
				return object(PromptEvaluation.read(journalPath), description);
			}

			@Override
			public String text(ObjectNode journal, String field, Path journalPath) throws IOException
			{
				return PromptEvaluation.text(journal, field, journalPath);
			}

			@Override
			public void write(Path journalPath, ObjectNode journal) throws IOException
			{
				PromptEvaluation.write(journalPath, journal);
			}
		});
		TrialClaims.configure(new TrialClaims.Access()
		{
			@Override
			public void withLock(Path runPath, TrialClaims.LockedOperation operation) throws IOException
			{
				withRunLock(runPath, () -> operation.execute(object(read(runPath), "run")));
			}

			@Override
			public ObjectNode read(Path runPath) throws IOException
			{
				return object(PromptEvaluation.read(runPath), "run");
			}

			@Override
			public void validateInputs(ObjectNode run, Path runPath) throws IOException
			{
				validateRunInputs(run, runPath);
			}

			@Override
			public Path suitePath(ObjectNode run, Path runPath) throws IOException
			{
				return path(text(run, "suite", runPath));
			}

			@Override
			public int trialLimit(ObjectNode run) throws IOException
			{
				return Benchmark.trialLimit(run);
			}

			@Override
			public boolean isTerminal(ObjectNode run, Path suitePath, Path runPath) throws IOException
			{
				return PromptEvaluation.isTerminal(run, suitePath, runPath);
			}

			@Override
			public void write(Path runPath, ObjectNode run) throws IOException
			{
				PromptEvaluation.write(runPath, run);
			}
		});
		Execution.configure(new Execution.Access()
		{
			@Override
			public HarnessEvaluationRunner defaultRunner(JsonMapper json)
			{
				return new NativeHarnessEvaluationRunner(json);
			}

			@Override
			public void publishActiveStatus(Path workspaceRoot, String phase) throws IOException
			{
				Lifecycle.publishActiveStatus(workspaceRoot, phase);
			}

			@Override
			public boolean isTerminal(Path runPath, Path suitePath) throws IOException
			{
				return PromptEvaluation.isTerminal(object(read(runPath), "run"), suitePath, runPath);
			}

			@Override
			public List<Integer> unrecordedTrials(Path runPath, String caseId, List<Integer> trials) throws IOException
			{
				return PromptEvaluation.unrecordedTrials(runPath, caseId, trials);
			}

			@Override
			public void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResult,
				Path validatorResult) throws IOException
			{
				withRunLock(runPath, () -> PromptEvaluation.record(runPath, caseId, trial, status, rawResult,
					validatorResult));
			}

			@Override
			public void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResult,
				Path validatorResult, Path checkResult) throws IOException
			{
				withRunLock(runPath, () -> PromptEvaluation.record(runPath, caseId, trial, status, rawResult,
					validatorResult, checkResult));
			}

			@Override
			public boolean hasActiveWorkerOwner(Path manifestPath) throws IOException
			{
				return Lifecycle.hasActiveWorkerOwner(manifestPath);
			}

			@Override
			public void prepareEmptyWorkspace(Path workspace, String operation) throws IOException
			{
				EvaluationWorkspaceLifecycle.prepareEmptyWorkspace(workspace, operation);
			}

			@Override
			public void prepareTrialContents(Path suitePath, String caseId, Path workspace) throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					TrialPreparation.prepare(scope.json(), suitePath, caseId, workspace);
				}
			}

			@Override
			public String bridgeInput(ObjectNode testCase, Path suitePath, Path prompt) throws IOException
			{
				return PromptEvaluation.bridgeInput(testCase, suitePath, prompt);
			}

			@Override
			public boolean directEvidenceValidator(ObjectNode validatorPlan, Path planPath) throws IOException
			{
				return PromptEvaluation.directEvidenceValidator(validatorPlan, planPath);
			}

			@Override
			public void prepareValidatorContents(Path retainedProject, Path rawResult, Path workspace,
				Set<String> exclusions) throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					ValidatorEvidence.describe(scope.json(), retainedProject, rawResult, workspace, exclusions,
						VALIDATOR_FINAL_RESPONSE);
				}
			}

			@Override
			public Set<String> validatorExcludedDirectories(ObjectNode plan, Path planPath) throws IOException
			{
				return PromptEvaluation.validatorExcludedDirectories(plan, planPath);
			}

			@Override
			public void stageDirectValidatorEvidence(Path retainedProject, Path rawResult, Path destination)
				throws IOException
			{
				PromptEvaluation.stageDirectValidatorEvidence(retainedProject, rawResult, destination);
			}

			@Override
			public Path directValidatorOutput(Path directEvidenceProject)
			{
				return PromptEvaluation.directValidatorOutput(directEvidenceProject);
			}

			@Override
			public Path directValidatorResponse(Path directEvidenceProject)
			{
				return PromptEvaluation.directValidatorResponse(directEvidenceProject);
			}

			@Override
			public Path writeValidatorPrompt(Path context, Path taskPrompt, ObjectNode testCase, Path workspace,
				Path retainedProject, Path rawResult, Path response) throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					return ValidatorPrompt.write(scope.json(), context, taskPrompt, testCase, workspace,
						retainedProject, rawResult, response);
				}
			}

			@Override
			public Path runDeterministicCheck(ObjectNode testCase, Path suitePath, Path retainedProject, Path rawResult,
				Path workspace, RoutingEvidence.CandidateRoute candidate) throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					return DeterministicChecks.run(scope.json(), testCase, suitePath, retainedProject, rawResult,
						workspace, candidate);
				}
			}

			@Override
			public boolean deterministicCheckFailed(Path result, ObjectNode testCase, Path prompt) throws IOException
			{
				return DeterministicChecks.failed(result, testCase, prompt);
			}

			@Override
			public HarnessEvaluationResult harnessResult(Path receiptPath, String receiptName) throws IOException
			{
				return PromptEvaluation.harnessResult(receiptPath, receiptName);
			}

			@Override
			public boolean hasSemanticAssertions(ExecutorEvidence evidence)
			{
				return PromptEvaluation.hasSemanticAssertions(evidence);
			}

			@Override
			public boolean deterministicAssertionsPassed(ExecutorEvidence evidence) throws IOException
			{
				return PromptEvaluation.deterministicAssertionsPassed(evidence);
			}

			@Override
			public void recordDeterministicCheckVerdict(Path runPath, ExecutorEvidence evidence) throws IOException
			{
				PromptEvaluation.recordDeterministicCheckVerdict(runPath, evidence);
			}

			@Override
			public String verdictKey(ExecutorEvidence evidence)
			{
				return PromptEvaluation.verdictKey(evidence);
			}

			@Override
			public void recordCrossTrialVerdict(Path runPath, ExecutorEvidence evidence, ObjectNode verdicts,
				Path validatorResult, WorkerTiming timing) throws IOException
			{
				PromptEvaluation.recordCrossTrialVerdict(runPath, evidence, verdicts, validatorResult, timing);
			}

			@Override
			public void recordIfUnrecorded(Path runPath, ExecutorEvidence evidence, AttemptStatus status,
				Path validatorResult) throws IOException
			{
				PromptEvaluation.recordIfUnrecorded(runPath, evidence, status, validatorResult);
			}
		});
		Benchmark.configure(() -> MAXIMUM_SUITE_RUNS);
		Timing.configure(PromptEvaluation::longValue);
		RouterPromotion.configure(new RouterPromotion.Access()
		{
			@Override
			public boolean hasCompletedMatrix(ObjectNode run)
			{
				return hasCompletedRoutingMatrix(run);
			}

			@Override
			public void writeAtomically(Path destination, ObjectNode receipt) throws IOException
			{
				PromptEvaluation.writeAtomically(destination, receipt);
			}
		});
		RoutingMatrix.configure(new RoutingMatrix.Access()
		{
			@Override
			public List<Integer> claimTrial(Path runPath) throws IOException
			{
				return claimTrialRange(runPath, 1, false);
			}

			@Override
			public List<RoutingMatrix.Evidence> execute(Path runPath, Path suitePath, List<ObjectNode> cases,
				List<Integer> trials, Path workspace, ObjectNode plan, int maximumConcurrency,
				HarnessEvaluationRunner runner) throws IOException
			{
				return ExecutionScheduler.executeRound(runPath, suitePath, cases, trials, workspace,
					plan, maximumConcurrency, true, runner).stream().map(evidence -> new RoutingMatrix.Evidence(
					evidence.testCase(), evidence.caseId(), evidence.trial(), evidence.rawResult())).toList();
			}

			@Override
			public ObjectNode result(Path runPath, ObjectNode suite, List<Integer> trials, Path workspace)
				throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					return RegressionCheck.summarize(scope.json(), runPath, suite, trials, workspace);
				}
			}

			@Override
			public void publish(Path runPath, ObjectNode result) throws IOException
			{
				withRunLock(runPath, () ->
				{
					ObjectNode record = object(read(runPath), "run");
					record.set("routing_matrix", result);
					write(runPath, record);
				});
			}

			@Override
			public void record(Path runPath, RoutingMatrix.Evidence evidence, AttemptStatus status, Path check)
				throws IOException
			{
				withRunLock(runPath, () -> PromptEvaluation.record(runPath, evidence.caseId(), evidence.trial(),
					status, evidence.rawResult(), check, check));
			}
		});
		RuntimeVerification.configure(new RuntimeVerification.Access()
		{
			@Override
			public Path verifiedArtifact(Path temporaryRoot, Path buildPlanPath) throws IOException
			{
				Path planPath = buildPlanPath.toAbsolutePath().normalize();
				if (!Files.isRegularFile(planPath, NOFOLLOW_LINKS))
					throw new IOException("Expected version-controlled build plan: " + planPath);
				ObjectNode plan = object(read(planPath), "build plan");
				Path workingDirectory = planPath(planPath.getParent(),
					text(plan, "working_directory", planPath), "Build working directory");
				Path artifact = planPath(workingDirectory,
					text(plan, "runner_artifact", planPath), "Runner artifact");
				Path runner = planPath(artifact, text(plan, "runner_launcher", planPath),
					"Runner launcher");
				if (!runner.startsWith(artifact))
					throw new IOException("Build plan runner launcher must be within its runner artifact: " + runner);
				if (!Files.isRegularFile(runner, NOFOLLOW_LINKS) || !Files.isExecutable(runner))
					throw new IOException("Build plan runner launcher is not executable: " + runner);
				return ExecutionPlan.verifyRunnerArtifact(runner, temporaryRoot, artifact);
			}
		});
		LifecycleSoak.configure(new LifecycleSoak.Access()
		{
			@Override
			public Path createInvocationDirectory(Path temporaryRoot, String operation) throws IOException
			{
				return InvocationDirectory.create(temporaryRoot, operation);
			}

			@Override
			public void deleteInvocationDirectory(Path temporaryRoot, Path directory) throws IOException
			{
				InvocationDirectory.delete(temporaryRoot, directory);
			}

			@Override
			public void begin(Path suite, Path prompt, String harness, String profile, Path context, Path runPath)
				throws IOException
			{
				PromptEvaluation.begin(suite, prompt, harness, profile, context, runPath);
			}

			@Override
			public List<Integer> claimTrials(Path runPath, int count) throws IOException
			{
				return claimTrialRange(runPath, count);
			}

			@Override
			public void prepareEmptyWorkspace(Path workspace, String operation) throws IOException
			{
				EvaluationWorkspaceLifecycle.prepareEmptyWorkspace(workspace, operation);
			}

			@Override
			public void prepareOwnedWorkspace(Path runPath, Path workspace) throws IOException
			{
				WORKSPACE_LIFECYCLE.prepare(runPath, workspace);
			}

			@Override
			public ObjectNode readObject(Path source, String description) throws IOException
			{
				return object(read(source), description);
			}

			@Override
			public void write(Path destination, ObjectNode record) throws IOException
			{
				PromptEvaluation.write(destination, record);
			}

			@Override
			public void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResult,
				Path validatorResult) throws IOException
			{
				PromptEvaluation.record(runPath, caseId, trial, status, rawResult, validatorResult);
			}

			@Override
			public ObjectNode decide(Path runPath) throws IOException
			{
				return PromptEvaluation.decide(runPath);
			}

			@Override
			public ObjectNode recover(Path runPath, Path workspace) throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					return Recovery.recoverInterruptedEvaluation(scope.json(), runPath, workspace);
				}
			}

			@Override
			public ObjectNode handoff(Path runPath, Path workspace, Path destination, Path temporaryRoot,
				Path workRoot) throws IOException
			{
				try (ToolScope scope = new ToolScope())
				{
					return PromptEvaluation.handoff(scope.json(), runPath, workspace, destination, temporaryRoot, workRoot);
				}
			}

			@Override
			public Path workRoot(Path temporaryRoot) throws IOException
			{
				return workRootForTemporaryResources(temporaryRoot);
			}

			@Override
			public void writeAtomically(Path destination, ObjectNode receipt) throws IOException
			{
				PromptEvaluation.writeAtomically(destination, receipt);
			}
		});
		EvaluatorSecrets.configure(() ->
		{
		});
		CandidateState.configure(new CandidateState.Access()
		{
			@Override
			public void validateRunInputs(ObjectNode run, Path runPath) throws IOException
			{
				PromptEvaluation.validateRunInputs(run, runPath);
			}

			@Override
			public void validateEvidenceIntegrity(ObjectNode run, Path runPath) throws IOException
			{
				PromptEvaluation.validateEvidenceIntegrity(run, runPath);
			}

			@Override
			public void withLock(Path recordPath, RecordStore.IoOperation operation) throws IOException
			{
				withRunLock(recordPath, operation::run);
			}

			@Override
			public void writeAtomically(Path destination, ObjectNode value) throws IOException
			{
				PromptEvaluation.writeAtomically(destination, value);
			}

			@Override
			public String candidateIdentity(ObjectNode run, Path runPath) throws IOException
			{
				return PromptEvaluation.candidateIdentity(run, runPath);
			}

			@Override
			public String candidateRevision(ObjectNode run, Path runPath) throws IOException
			{
				return Identity.candidateRevision(run, runPath);
			}

			@Override
			public boolean hasCompletedRegressionCheck(ObjectNode run)
			{
				return PromptEvaluation.hasCompletedRegressionCheck(run);
			}

			@Override
			public boolean hasEpochSupersession(ObjectNode state, Path statePath, String currentEpoch,
				String proposedEpoch) throws IOException
			{
				return RuntimeRevalidation.hasSupersession(state, statePath, currentEpoch, proposedEpoch);
			}
		});
		RevisionReview.configure(new RevisionReview.Access()
		{
			@Override
			public void validateRunInputs(ObjectNode run, Path runPath) throws IOException
			{
				PromptEvaluation.validateRunInputs(run, runPath);
			}

			@Override
			public String candidateRevision(ObjectNode run, Path runPath) throws IOException
			{
				return Identity.candidateRevision(run, runPath);
			}
		});
		RuntimeRevalidation.configure(new RuntimeRevalidation.Access()
		{
			@Override
			public void withLock(Path recordPath, RecordStore.IoOperation operation) throws IOException
			{
				withRunLock(recordPath, operation::run);
			}

			@Override
			public void writeAtomically(Path destination, ObjectNode value) throws IOException
			{
				PromptEvaluation.writeAtomically(destination, value);
			}

			@Override
			public ObjectNode classifyFailure(Path runPath, String caseId, int trial) throws IOException
			{
				return PromptEvaluation.classifyFailure(runPath, caseId, trial);
			}

			@Override
			public boolean hasCompletedSmoke(ObjectNode run)
			{
				return PromptEvaluation.hasCompletedSmoke(run);
			}
		});
		GoalStatus.configure(new GoalStatus.Access()
		{
			@Override
			public ObjectNode read(Path path, String description) throws IOException
			{
				return object(PromptEvaluation.read(path), description);
			}

			@Override
			public void validateRunInputs(ObjectNode run, Path path) throws IOException
			{
				PromptEvaluation.validateRunInputs(run, path);
			}

			@Override
			public boolean hasCompletedRegressionCheck(ObjectNode run)
			{
				return PromptEvaluation.hasCompletedRegressionCheck(run);
			}

			@Override
			public String sha256(Path artifact) throws IOException
			{
				return PromptEvaluation.sha256(artifact);
			}

			@Override
			public String suiteFingerprint(Path suitePath) throws IOException
			{
				return PromptEvaluation.suiteFingerprint(suitePath);
			}
		});
	}
	/**
	 * Creates candidate lifecycle services for one operation mapper.
	 *
	 * @param json the operation's JSON mapper
	 * @return candidate lifecycle services bound to that mapper
	 */
	private static CandidateLifecycle candidateLifecycle(JsonMapper json)
	{
		return new CandidateLifecycle(json, CANDIDATE_ACCESS, new CandidateLifecycle.Operations()
		{
			@Override
			public ObjectNode regressionCheck(CandidateLifecycle.Request request) throws IOException
			{
				return PromptEvaluation.regressionCheck(request.temporaryRoot(), request.candidateStatePath(),
					request.suitePath(), request.promptPath(), request.harness(), request.contextPath(), request.runner(),
					request.buildPlanPath(), request.retainedDirectory(), request.maximumConcurrency(),
					request.evaluationRunner());
			}

			@Override
			public ObjectNode certifyPromoted(CandidateLifecycle.Request request,
				CandidateController controller) throws IOException
			{
				return certifyPromotedOwned(request.temporaryRoot(), request.candidateStatePath(),
					request.suitePath(), request.promptPath(), request.harness(), request.contextPath(), request.runner(),
					request.buildPlanPath(), request.retainedDirectory(), request.maximumConcurrency(),
					request.evaluationRunner(), controller);
			}

			@Override
			public ObjectNode resumePendingCertification(Path candidateStatePath, int maximumConcurrency)
				throws IOException
			{
				return PromptEvaluation.resumePendingCertification(candidateStatePath, maximumConcurrency);
			}

			@Override
			public ObjectNode promote(Path checkRunPath, Path candidateStatePath) throws IOException
			{
				return candidateStatePromotion(checkRunPath, candidateStatePath);
			}
		});
	}

	static
	{
		Runtime.getRuntime().addShutdownHook(new Thread(PromptEvaluation::interruptActiveEvaluations,
			"prompt-evaluation-command-shutdown"));
	}
	private record SuiteOutcome(int trial, boolean passed)
	{
	}

	/**
	 * Executes one prompt-evaluation command.
	 *
	 * @param args a command and its arguments
	 * @throws IOException if an input or evaluation record is invalid
	 */
	public static void main(String[] args) throws IOException
	{
		PublicCommands.execute(args, command -> run(command, System.out::println));
	}

	/**
	 * Executes a workflow composition step for a package peer.
	 *
	 * <p>
	 * This is not a command-line entry point. Public callers use {@link #main(String[])} so they can only start a
	 * complete operation or inspect and recover its result.
	 *
	 * @param args an internal workflow step and its arguments
	 * @return caller-visible output from that step
	 * @throws IOException if an input or evaluation record is invalid
	 */
	static String run(String[] args) throws IOException
	{
		StringBuilder output = new StringBuilder();
		run(args, line -> output.append(line).append('\n'));
		return output.toString();
	}

	/**
	 * Executes a workflow composition step with an owned caller-output destination.
	 *
	 * @param args an internal workflow step and its arguments
	 * @param output the destination for command output
	 * @throws IOException if an input or evaluation record is invalid
	 */
	static void run(String[] args, Consumer<String> output) throws IOException
	{
		try (ToolScope operationScope = new ToolScope())
		{
			run(operationScope, args, output);
		}
	}

	/**
	 * Executes a workflow composition step with one operation-owned scope.
	 *
	 * @param operationScope the scope that owns this operation's services
	 * @param args an internal workflow step and its arguments
	 * @param output the destination for command output
	 * @throws IOException if an input or evaluation record is invalid
	 */
	private static void run(ToolScope operationScope, String[] args, Consumer<String> output) throws IOException
	{
		requireThat(operationScope, "operationScope").isNotNull();
		PromptEvaluationOperation operation = new PromptEvaluationOperation(operationScope);
		JsonMapper json = operation.json();
		requireThat(args, "args").isNotNull();
		requireThat(output, "output").isNotNull();
		if (args.length == 2 && args[0].equals("validate-suite"))
		{
			SuiteContract.validate(path(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("lifecycle-soak"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(RuntimeVerification.lifecycleSoakFromPlan(
					scope.temporaryWorkRoot(), path(args[1]))));
			}
			return;
		}
		if (args.length == 5 && args[0].equals("create-direct-diagnostic"))
		{
			createDirectDiagnostic(json, path(args[1]), args[2], Integer.parseInt(args[3]), path(args[4]));
			return;
		}
		if (args.length == 5 && args[0].equals("create-routing-diagnostic"))
		{
			createRoutingDiagnostic(json, path(args[1]), args[2], Integer.parseInt(args[3]), path(args[4]));
			return;
		}
		if (args.length == 4 && args[0].equals("audit-routing"))
		{
			RouterPromotion.audit(path(args[1]), path(args[2]), path(args[3]));
			return;
		}
		if (args.length == 3 && args[0].equals("audit-generalization"))
		{
			Generalization.audit(path(args[1]), path(args[2]));
			return;
		}
		if (args.length == 5 && args[0].equals("promote-router"))
		{
			output.accept(json.writeValueAsString(RouterPromotion.promote(json, path(args[1]), path(args[2]), path(args[3]),
				path(args[4]))));
			return;
		}
		if (args.length == 2 && args[0].equals("preflight"))
		{
			try (ToolScope scope = new ToolScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path workspace = cleanup.add(InvocationDirectory.create(scope.temporaryWorkRoot(),
					"prompt-evaluation-preflight"));
				preflight(json, path(args[1]), workspace);
			}
			return;
		}
		if (args.length == 2 && args[0].equals("precheck"))
		{
			try (ToolScope scope = new ToolScope(); TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path workspace = cleanup.add(InvocationDirectory.create(scope.temporaryWorkRoot(),
					"prompt-evaluation-precheck"));
				preflight(json, path(args[1]), workspace);
			}
			return;
		}
		if (args.length == 3 && args[0].equals("assemble"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(assemble(scope.temporaryWorkRoot(), path(args[1]), path(args[2]))));
			}
			return;
		}
		if (args.length == 8 && args[0].equals("prepare"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(prepare(scope.temporaryWorkRoot(), path(args[1]), path(args[2]),
					args[3],
					path(args[4]), path(args[5]), path(args[6]), path(args[7]))));
			}
			return;
		}
		if (args.length == 8 && args[0].equals("verify-reviewer-controls"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(verifyReviewerControls(json, scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]), args[3], path(args[4]), path(args[5]), path(args[6]), path(args[7]))));
			}
			return;
		}
		if (args.length >= 1 && args[0].equals("evaluate"))
		{
			throw new IOException("Formal certification requires a successful regression check and promotion. Run " +
				"regression-check, promote, then certify-promoted.");
		}
		if ((args.length == 10 || args.length == 12) && args[0].equals("regression-check"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(regressionCheck(scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]), path(args[3]), path(args[4]), args[5], path(args[6]), path(args[7]), path(args[8]),
					path(args[9]), maximumConcurrency(args, 10))));
			}
			return;
		}
		if (args.length == 4 && args[0].equals("revision-review"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(RevisionReview.run(scope.json(), scope.temporaryWorkRoot(),
					path(args[1]),
					path(args[2]), path(args[3]))));
			}
			return;
		}
		if (args.length == 3 && args[0].equals("revalidate-runtime"))
		{
			output.accept(json.writeValueAsString(RuntimeRevalidation.revalidate(path(args[1]),
				path(args[2]))));
			return;
		}
		if ((args.length == 8 || args.length == 10) && args[0].equals("benchmark"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(benchmark(scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]), args[3], path(args[4]), path(args[5]), path(args[6]), path(args[7]),
					maximumConcurrency(args, 8))));
			}
			return;
		}
		if (args.length >= 4 && args[0].equals("materialize-candidate"))
		{
			try (ToolScope scope = new ToolScope())
			{
				CandidateMaterializationInput input = candidateMaterializationInput(Arrays.asList(args).subList(4,
					args.length));
				output.accept(json.writeValueAsString(materializeCandidateBundle(scope.catWork(), path(args[1]),
					path(args[2]), args[3], input.additionalRules(), input.routeReplacements())));
			}
			return;
		}
		if (args.length >= 6 && args[0].equals("materialize-candidate-git"))
		{
			try (ToolScope scope = new ToolScope())
			{
				CandidateMaterializationInput input = candidateMaterializationInput(Arrays.asList(args).subList(6,
					args.length));
				output.accept(json.writeValueAsString(materializeCandidateBundleFromGit(scope.catWork(), path(args[1]),
					args[2], args[3], path(args[4]), args[5], input.additionalRules(), input.routeReplacements())));
			}
			return;
		}
		if ((args.length == 10 || args.length == 12) && args[0].equals("benchmark-pair"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(benchmarkPair(scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]), path(args[3]), args[4], path(args[5]), path(args[6]), path(args[7]), path(args[8]),
					Integer.parseInt(args[9]), maximumConcurrency(args, 10))));
			}
			return;
		}
		if ((args.length == 9 || args.length == 11) && args[0].equals("certify-promoted"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(certifyPromoted(scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]),
					path(args[3]), args[4], path(args[5]), path(args[6]), path(args[7]), path(args[8]),
					maximumConcurrency(args, 9))));
			}
			return;
		}
		if ((args.length == 10 || args.length == 12) && args[0].equals("routing-diagnostic"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(routingDiagnostic(json, scope.temporaryWorkRoot(), path(args[1]), args[2],
					Integer.parseInt(args[3]), path(args[4]), args[5], path(args[6]), path(args[7]), path(args[8]),
					path(args[9]), maximumConcurrency(args, 10))));
			}
			return;
		}
		if ((args.length == 9 || args.length == 11) && args[0].equals("smoke-evaluate"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(smokeEvaluate(scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]),
					args[3], path(args[4]), path(args[5]), path(args[6]), args[7], path(args[8]),
					maximumConcurrency(args, 9))));
			}
			return;
		}
		if ((args.length == 13 || args.length == 15) && args[0].equals("capability-diagnostic"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(capabilityDiagnostic(json, scope.temporaryWorkRoot(), path(args[1]),
					args[2],
					Integer.parseInt(args[3]), path(args[4]), path(args[5]), args[6], path(args[7]), path(args[8]),
					path(args[9]), args[10], args[11], path(args[12]), maximumConcurrency(args, 13))));
			}
			return;
		}
		if (args.length == 7 && args[0].equals("begin"))
		{
			begin(path(args[1]), path(args[2]), args[3], args[4], path(args[5]), path(args[6]));
			return;
		}
		if (args.length == 3 && args[0].equals("bind-plan"))
		{
			bindExecutionPlan(path(args[1]), path(args[2]));
			return;
		}
		if (args.length == 3 && args[0].equals("claim-trials"))
		{
			claimTrials(path(args[1]), Integer.parseInt(args[2]), output);
			return;
		}
		if (args.length == 4 && args[0].equals("prepare-trial"))
		{
			prepareTrial(json, path(args[1]), args[2], path(args[3]), output);
			return;
		}
		if (args.length == 4 && args[0].equals("prepare-validator"))
		{
			prepareValidator(json, path(args[1]), path(args[2]), path(args[3]), output);
			return;
		}
		if (args.length == 6 && args[0].equals("execute"))
		{
			execute(path(args[1]), args[2], Integer.parseInt(args[3]), path(args[4]), path(args[5]), output);
			return;
		}
		if ((args.length == 5 || args.length == 7) && args[0].equals("run-batch"))
		{
			output.accept(json.writeValueAsString(runBatch(path(args[1]), Integer.parseInt(args[2]), path(args[3]),
				path(args[4]), maximumConcurrency(args, 5))));
			return;
		}
		if ((args.length == 5 || args.length == 7) && args[0].equals("smoke"))
		{
			output.accept(json.writeValueAsString(smoke(path(args[1]), args[2], path(args[3]), path(args[4]),
				maximumConcurrency(args, 5))));
			return;
		}
		if ((args.length == 4 || args.length == 6) && args[0].equals("run-regression-check"))
		{
			output.accept(json.writeValueAsString(runRegressionCheck(path(args[1]), path(args[2]), path(args[3]),
				maximumConcurrency(args, 4))));
			return;
		}
		if ((args.length == 4 || args.length == 6) && args[0].equals("run-routing-matrix"))
		{
			output.accept(json.writeValueAsString(runRoutingMatrix(path(args[1]), path(args[2]), path(args[3]),
				maximumConcurrency(args, 4))));
			return;
		}
		if (args.length == 3 && args[0].equals("promote"))
		{
			output.accept(json.writeValueAsString(candidateStatePromotion(path(args[1]), path(args[2]))));
			return;
		}
		if (args.length == 3 && args[0].equals("record-regression-check-signatures"))
		{
			output.accept(json.writeValueAsString(candidateStateCheckSignatures(path(args[1]),
				path(args[2]))));
			return;
		}
		if ((args.length == 4 || args.length == 6) && args[0].equals("run-until-terminal"))
		{
			output.accept(json.writeValueAsString(runUntilTerminal(path(args[1]), path(args[2]), path(args[3]),
				maximumConcurrency(args, 4))));
			return;
		}
		if (args.length == 4 && args[0].equals("handoff"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(handoff(json, path(args[1]), path(args[2]), path(args[3]),
					scope.temporaryWorkRoot(), scope.catWork())));
			}
			return;
		}
		if (args.length == 3 && args[0].equals("link-correction"))
		{
			CorrectionJournal.link(path(args[1]), path(args[2]));
			return;
		}
		if (args.length == 7 && args[0].equals("record"))
		{
			record(path(args[1]), args[2], Integer.parseInt(args[3]), args[4], args[5], args[6]);
			return;
		}
		if (args.length == 2 && args[0].equals("decide"))
		{
			output.accept(json.writeValueAsString(decide(path(args[1]))));
			return;
		}
		if ((args.length == 2 || args.length == 3) && args[0].equals("terminate"))
		{
			Path runPath = path(args[1]);
			if (args.length == 2)
				Recovery.terminateWorkers(runPath);
			else
				Recovery.terminateWorkers(runPath, path(args[2]));
			return;
		}
		if ((args.length == 2 || args.length == 3) && args[0].equals("recover"))
		{
			Path runPath = path(args[1]);
			ObjectNode result;
			if (args.length == 2)
				result = Recovery.recoverInterruptedEvaluation(json, runPath);
			else
				result = Recovery.recoverInterruptedEvaluation(json, runPath, path(args[2]));
			output.accept(json.writeValueAsString(result));
			return;
		}
		if ((args.length == 2 || args.length == 3 || args.length == 4) && args[0].equals("status"))
		{
			boolean summary = args[args.length - 1].equals("--summary");
			int positionalArguments = args.length;
			if (summary)
				--positionalArguments;
			if (positionalArguments != 2 && positionalArguments != 3)
				throw new IllegalArgumentException("status accepts <run.json> [workspace-root] [--summary]");
			ObjectNode result;
			if (positionalArguments == 2)
				result = Lifecycle.status(json, path(args[1]), summary);
			else
				result = Lifecycle.status(json, path(args[1]), path(args[2]), summary);
			output.accept(json.writeValueAsString(result));
			return;
		}
		if ((args.length == 3 || args.length == 4) && args[0].equals("compare"))
		{
			if (args.length == 4 && !args[3].equals("--diagnostic"))
				throw new IllegalArgumentException("compare accepts <baseline-run.json> <candidate-run.json> [--diagnostic]");
			output.accept(json.writeValueAsString(compare(path(args[1]), path(args[2]), args.length == 4)));
			return;
		}
		if ((args.length == 2 || args.length == 3) && args[0].equals("report"))
		{
			if (args.length == 3 && !args[2].equals("--performance"))
				throw new IllegalArgumentException("report accepts <run.json> [--performance]");
			output.accept(json.writeValueAsString(report(path(args[1]), args.length == 3)));
			return;
		}
		if ((args.length == 2 || args.length == 3) &&
			(args[0].equals("goal-status") || args[0].equals("candidate-status")))
		{
			Path candidateStatePath = path(args[1]);
			Path goalPath = null;
			if (args.length == 3)
				goalPath = path(args[2]);
			output.accept(json.writeValueAsString(GoalStatus.status(json, candidateStatePath, goalPath)));
			return;
		}
		if (args.length == 4 && args[0].equals("classify-failure"))
		{
			output.accept(json.writeValueAsString(classifyFailure(path(args[1]), args[2], Integer.parseInt(args[3]))));
			return;
		}
		if ((args.length == 4 || args.length == 5) && args[0].equals("finalize"))
		{
			try (ToolScope scope = new ToolScope())
			{
				Path destination = null;
				if (args.length == 5)
					destination = path(args[4]);
				ObjectNode result = finalizeEvaluation(json, scope.temporaryWorkRoot(), scope.catWork(), path(args[1]),
					path(args[2]), args[3], destination);
				output.accept(json.writeValueAsString(result));
			}
			return;
		}
		if (args.length >= 5 && args[0].equals("review-bundle"))
		{
			try (ToolScope scope = new ToolScope())
			{
				List<Path> evidence = Arrays.stream(args).skip(4).map(PromptEvaluation::path).toList();
				ObjectNode receipt = CandidateReviews.createBundle(scope.json(), scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]), args[3], evidence);
				output.accept(json.writeValueAsString(receipt));
			}
			return;
		}
		if (args.length == 4 && args[0].equals("prepare-recurrence-review"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(prepareRecurrenceReview(scope.temporaryWorkRoot(), path(args[1]),
					path(args[2]), path(args[3]))));
			}
			return;
		}
		if (args.length == 4 && args[0].equals("record-recurrence-review"))
		{
			output.accept(json.writeValueAsString(recordRecurrenceReview(path(args[1]), path(args[2]), path(args[3]))));
			return;
		}
		if (args.length == 4 && args[0].equals("prepare-revision-review"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(RevisionReview.prepare(scope.json(), scope.temporaryWorkRoot(),
					path(args[1]),
					path(args[2]), path(args[3]))));
			}
			return;
		}
		if (args.length == 4 && args[0].equals("record-revision-review"))
		{
			try (ToolScope scope = new ToolScope())
			{
				output.accept(json.writeValueAsString(RevisionReview.record(scope.json(), path(args[1]), path(args[2]),
					path(args[3]))));
			}
			return;
		}
		if (args.length == 5 && args[0].equals("supersede-evaluation-epoch"))
		{
			output.accept(json.writeValueAsString(
				RuntimeRevalidation.authorizeDefectSupersession(path(args[1]), path(args[2]), args[3],
					Integer.parseInt(args[4]))));
			return;
		}
		if (args.length == 0)
			throw new IllegalArgumentException("An internal prompt-evaluation workflow step is required.");
		throw new IllegalArgumentException("Unknown internal prompt-evaluation workflow step: " + args[0]);
	}

	/**
	 * Separates candidate rule paths from explicit ordinary-to-candidate route mappings supplied to a materialization
	 * command.
	 *
	 * @param arguments the command arguments after its required primary-rule input
	 * @return the additional candidate rules and route mappings
	 * @throws IOException if a mapping is incomplete, duplicated, or malformed
	 */
	private static CandidateMaterializationInput candidateMaterializationInput(List<String> arguments)
		throws IOException
	{
		List<String> rules = new ArrayList<>();
		Map<String, String> routes = new TreeMap<>();
		for (int index = 0; index < arguments.size(); ++index)
		{
			String argument = arguments.get(index);
			if (!argument.equals("--route-replacement"))
			{
				rules.add(argument);
				continue;
			}
			if (index + 2 >= arguments.size())
			{
				throw new IOException("--route-replacement requires an ordinary route and a candidate route");
			}
			index += 1;
			String ordinary = arguments.get(index);
			index += 1;
			String candidate = arguments.get(index);
			if (ordinary.startsWith("--") || candidate.startsWith("--") || routes.putIfAbsent(ordinary, candidate) != null)
			{
				throw new IOException("Candidate route replacements must have distinct ordinary and candidate rule paths");
			}
		}
		return new CandidateMaterializationInput(List.copyOf(rules), Map.copyOf(routes));
	}

	/**
	 * Groups the parsed candidate-bundle materialization inputs.
	 *
	 * @param additionalRules the selected companion rule paths
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical rule paths
	 */
	private record CandidateMaterializationInput(List<String> additionalRules, Map<String, String> routeReplacements)
	{
	}

	/**
	 * Checks the independent reviewer's required pass and fail responses without launching an executor trial or recording
	 * compliance.
	 *
	 * @param json the operation's JSON mapper
	 * @param temporaryRoot the CAT work temp directory
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact whose runner/profile will be used later
	 * @param harness the selected harness identifier
	 * @param contextPath the version-controlled validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the selected build plan that produces the runner artifact
	 * @param retainedDirectory the initially absent retained directory below the CAT work temp directory
	 * @return the prepared execution identity and retained semantic-control receipt
	 * @throws IOException if a reviewer control cannot establish its declared isolated result
	 */
	static ObjectNode verifyReviewerControls(JsonMapper json, Path temporaryRoot, Path suitePath, Path promptPath,
		String harness,
		Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory) throws IOException
	{
		validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		preflightCached(temporaryRoot, suitePath);
		ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
		ObjectNode prepared = prepare(temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly,
			retainedDirectory);
		Path run = path(prepared.path("run").asString());
		Path workspace = path(prepared.path("workspace").asString());
		Path plan = path(prepared.path("execution_plan").asString());
		bindExecutionPlan(run, plan);
		ObjectNode controls;
		try
		{
			verifySemanticControls(run, workspace, plan);
			controls = recordReviewerControls(json, run, workspace, true, null);
		}
		catch (IOException e)
		{
			recordReviewerControls(json, run, workspace, false, e.getMessage());
			throw e;
		}
		prepared.set("assembly", assembly);
		prepared.set("reviewer_controls", controls);
		return prepared;
	}

	/**
	 * Records the terminal result of the reviewer-control operation.
	 *
	 * <p>
	 * The control receipt establishes whether the independent reviewer correctly distinguishes the deliberately
	 * passing and failing responses. Recording that result makes the operation terminal even when it cannot proceed to
	 * an ordinary evaluator decision, so status and finalization do not mistake a completed calibration for an abandoned
	 * evaluation.
	 *
	 * @param json the operation's JSON mapper
	 * @param runPath the prepared evaluation record
	 * @param workspaceRoot the evaluator-owned workspace that contains the control receipt
	 * @param passed whether every declared control received its expected verdict
	 * @param failure the verifier failure when controls did not pass, or {@code null} otherwise
	 * @return the terminal reviewer-control result
	 * @throws IOException if the record cannot be updated
	 */
	static ObjectNode recordReviewerControls(JsonMapper json, Path runPath, Path workspaceRoot, boolean passed,
		String failure)
		throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		ObjectNode result = json.createObjectNode();
		String status;
		if (passed)
			status = "passed";
		else
			status = "failed";
		result.put("status", status);
		result.put("completed_at_epoch_millis", System.currentTimeMillis());
		Path receipt = normalizedWorkspaceRoot.resolve("semantic-control-check.json");
		if (Files.isRegularFile(receipt, NOFOLLOW_LINKS))
		{
			result.put("semantic_control_receipt", normalizedWorkspaceRoot.relativize(receipt).toString());
			result.put("semantic_control_receipt_sha256", sha256(receipt));
		}
		if (!passed)
			result.put("failure", failure);

		withRunLock(normalizedRunPath, () ->
		{
			ObjectNode run = object(read(normalizedRunPath), "reviewer-control run");
			if (run.has("mode"))
				throw new IOException("Evaluation record already has an execution mode: " + normalizedRunPath);
			run.put("mode", "reviewer_controls");
			run.set("reviewer_controls", result);
			write(normalizedRunPath, run);
		});
		return result;
	}

	/**
	 * Runs every durable organic testcase once before a candidate can become the next evaluation baseline.
	 *
	 * <p>
	 * This check is intentionally not a compliance measurement. It answers the different question of whether the
	 * candidate regressed any case that the durable suite covers, while retaining the same preparation, runner, and
	 * independent-validation evidence used by formal evaluation.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param smokeRunPath the retained successful smoke evaluation for this candidate
	 * @param candidateStatePath the durable promotion and failure-signature record
	 * @param suitePath the durable suite
	 * @param promptPath the candidate prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent retained result directory
	 * @param maximumConcurrency the shared worker capacity
	 * @return the prepared record and terminal regression-check result
	 * @throws IOException if preparation or checking cannot retain a terminal result
	 */
	static ObjectNode regressionCheck(Path temporaryRoot, Path smokeRunPath, Path candidateStatePath, Path suitePath,
		Path promptPath,
		String harness,
		Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency)
		throws IOException
	{
		validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		validateDurableCandidateStatePath(temporaryRoot, candidateStatePath);
		preflightCached(temporaryRoot, suitePath);
		ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
		ObjectNode prepared = prepare(temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly,
			retainedDirectory);
		prepared.set("assembly", assembly);
		Path run = path(prepared.path("run").asString());
		Path workspace = path(prepared.path("workspace").asString());
		Path plan = path(prepared.path("execution_plan").asString());
		bindExecutionPlan(run, plan);
		requireSuccessfulComparableSmoke(smokeRunPath, run);
		discardCandidateCheckEvidence(candidateStatePath);
		CandidateState.validateCandidateStateBeforeCheck(candidateStatePath, run);
		bindCandidateState(run, candidateStatePath);
		ObjectNode check = runRegressionCheck(run, workspace, plan, maximumConcurrency);
		prepared.set("regression_check", check);
		if (!check.path("status").asString().equals("incomplete"))
			prepared.set("candidate_state", candidateStateCheckSignatures(run, candidateStatePath));
		if (check.path("status").asString().equals("passed"))
			prepared.set("promotion", candidateStatePromotion(run, candidateStatePath));
		return prepared;
	}

	/**
	 * Runs a package-local regression check without a smoke prerequisite.
	 *
	 * <p>
	 * Tests use this focused operation to establish regression-check behavior. The public CLI always uses the overload
	 * that verifies a successful comparable smoke receipt before it starts an all-case model run.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param candidateStatePath the durable promotion and failure-signature record
	 * @param suitePath the durable suite
	 * @param promptPath the candidate prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent retained result directory
	 * @param maximumConcurrency the shared worker capacity
	 * @return the prepared record and terminal regression-check result
	 * @throws IOException if preparation or checking cannot retain a terminal result
	 */
	static ObjectNode regressionCheck(Path temporaryRoot, Path candidateStatePath, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return regressionCheck(temporaryRoot, candidateStatePath, suitePath, promptPath, harness, contextPath,
				runner, buildPlanPath, retainedDirectory, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs a package-local regression check with the supplied harness-evaluation runner.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param candidateStatePath the durable promotion and failure-signature record
	 * @param suitePath the durable suite
	 * @param promptPath the candidate prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent retained result directory
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that executes each evaluator request
	 * @return the prepared record and terminal regression-check result
	 * @throws IOException if preparation or checking cannot retain a terminal result
	 */
	static ObjectNode regressionCheck(Path temporaryRoot, Path candidateStatePath, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency,
		HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		validateDurableCandidateStatePath(temporaryRoot, candidateStatePath);
		preflightCached(temporaryRoot, suitePath);
		ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
		ObjectNode prepared = prepare(temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly,
			retainedDirectory);
		prepared.set("assembly", assembly);
		Path run = path(prepared.path("run").asString());
		Path workspace = path(prepared.path("workspace").asString());
		Path plan = path(prepared.path("execution_plan").asString());
		bindExecutionPlan(run, plan);
		discardCandidateCheckEvidence(candidateStatePath);
		CandidateState.validateCandidateStateBeforeCheck(candidateStatePath, run);
		bindCandidateState(run, candidateStatePath);
		ObjectNode check = runRegressionCheck(run, workspace, plan, maximumConcurrency, evaluationRunner);
		prepared.set("regression_check", check);
		if (!check.path("status").asString().equals("incomplete"))
			prepared.set("candidate_state", candidateStateCheckSignatures(run, candidateStatePath));
		if (check.path("status").asString().equals("passed"))
			prepared.set("promotion", candidateStatePromotion(run, candidateStatePath));
		return prepared;
	}

	/**
	 * Records the candidate ledger that supplies active failures for one candidate evaluation.
	 *
	 * @param runPath the prepared evaluation record
	 * @param candidateStatePath the durable promotion and failure-signature ledger
	 * @throws IOException if the record cannot be updated
	 */
	private static void bindCandidateState(Path runPath, Path candidateStatePath) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedCandidateStatePath = candidateStatePath.toAbsolutePath().normalize();
		withRunLock(normalizedRunPath, () ->
		{
			ObjectNode run = object(read(normalizedRunPath), "prepared candidate evaluation run");
			run.put("failure_signature_state", normalizedCandidateStatePath.toString());
			write(normalizedRunPath, run);
		});
	}

	/**
 * Measures one candidate bundle without promoting it or changing a candidate-state record.
	 *
	 * @param temporaryRoot the CAT work temp directory for short-lived build work
	 * @param suitePath the durable scenario suite
 * @param candidateBundlePath the immutable rules installed for this measurement
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent evidence directory for this benchmark
	 * @param maximumConcurrency the shared worker capacity
	 * @return the prepared record, assembly identity, and terminal benchmark decision
	 * @throws IOException if a prerequisite or benchmark batch cannot produce terminal evidence
	 */
	static ObjectNode benchmark(Path temporaryRoot, Path suitePath, Path candidateBundlePath, String harness,
		Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return benchmark(temporaryRoot, suitePath, candidateBundlePath, harness, contextPath, runner, buildPlanPath,
				retainedDirectory, maximumConcurrency, new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Measures one candidate bundle with the supplied typed evaluator runner.
	 *
	 * @param temporaryRoot the CAT work temp directory for short-lived build work
	 * @param suitePath the durable scenario suite
	 * @param candidateBundlePath the immutable rules installed for this measurement
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent evidence directory for this benchmark
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that retains executor and validator results
	 * @return the prepared record, assembly identity, and terminal benchmark decision
	 * @throws IOException if a prerequisite or benchmark batch cannot produce terminal evidence
	 */
	static ObjectNode benchmark(Path temporaryRoot, Path suitePath, Path candidateBundlePath, String harness,
		Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency,
		HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		CandidateBundle.Candidate candidate;
		try (ToolScope scope = new ToolScope())
		{
			candidate = CandidateBundle.read(scope.json(), candidateBundlePath);
		}
		Benchmark.SingleRequest request = new Benchmark.SingleRequest(temporaryRoot,
			suitePath, candidate, harness, contextPath, runner, buildPlanPath, retainedDirectory, maximumConcurrency,
			evaluationRunner);
		try (ToolScope scope = new ToolScope())
		{
			return benchmark(scope.json()).execute(request);
		}
	}

	/**
	 * Materializes one selected prompt candidate below the CAT work root.
	 *
	 * @param workRoot the CAT work root that retains the immutable candidate
	 * @param sourceRules the source tree whose paths match installed rule paths
	 * @param bundleDirectory the initially absent destination below the work root
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules the other canonical rule paths required for this candidate
	 * @return the published bundle location and immutable receipt
	 * @throws IOException if the destination is unsafe or the selected source rules cannot form a valid bundle
	 */
	static ObjectNode materializeCandidateBundle(Path workRoot, Path sourceRules, Path bundleDirectory,
		String primaryRule, List<String> additionalRules) throws IOException
	{
		return materializeCandidateBundle(workRoot, sourceRules, bundleDirectory, primaryRule, additionalRules, Map.of());
	}

	/**
	 * Materializes one selected prompt candidate below the CAT work root with its ordinary-to-candidate route mapping.
	 *
	 * @param workRoot the CAT work root that retains the immutable candidate
	 * @param sourceRules the source tree whose paths match installed rule paths
	 * @param bundleDirectory the initially absent destination below the work root
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules the other canonical rule paths required for this candidate
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical rule paths
	 * @return the published bundle location and immutable receipt
	 * @throws IOException if the destination is unsafe or the selected source rules cannot form a valid bundle
	 */
	static ObjectNode materializeCandidateBundle(Path workRoot, Path sourceRules, Path bundleDirectory,
		String primaryRule, List<String> additionalRules, Map<String, String> routeReplacements) throws IOException
	{
		Path work = workRoot.toAbsolutePath().normalize();
		Path bundle = bundleDirectory.toAbsolutePath().normalize();
		if (!bundle.startsWith(work) || bundle.equals(work))
			throw new IOException("Candidate bundle must remain beneath the CAT work root: " + bundle);
		CandidateBundle.Candidate candidate;
		ObjectNode receipt;
		try (ToolScope scope = new ToolScope())
		{
			candidate = CandidateBundle.materialize(scope.json(), sourceRules, bundle, primaryRule, additionalRules,
				routeReplacements);
			receipt = CandidateBundle.receipt(scope.json(), candidate);
		}
		receipt.put("bundle", bundle.toString());
		return receipt;
	}

	/**
	 * Materializes one selected historical prompt candidate below the CAT work root.
	 *
	 * @param workRoot the CAT work root that retains the immutable candidate
	 * @param repository the Git repository that contains the historical source rules
	 * @param revision the immutable Git revision that selects their contents
	 * @param repositoryRelativeRulesRoot the source rules root relative to the repository
	 * @param bundleDirectory the initially absent destination below the work root
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules the other canonical rule paths required for this candidate
	 * @return the published bundle location and immutable receipt
	 * @throws IOException if the destination is unsafe or the selected historical rules cannot form a valid bundle
	 */
	static ObjectNode materializeCandidateBundleFromGit(Path workRoot, Path repository, String revision,
		String repositoryRelativeRulesRoot, Path bundleDirectory, String primaryRule, List<String> additionalRules)
		throws IOException
	{
		return materializeCandidateBundleFromGit(workRoot, repository, revision, repositoryRelativeRulesRoot,
			bundleDirectory, primaryRule, additionalRules, Map.of());
	}

	/**
	 * Materializes one selected historical prompt candidate below the CAT work root with its ordinary-to-candidate route
	 * mapping.
	 *
	 * @param workRoot the CAT work root that retains the immutable candidate
	 * @param repository the Git repository that contains the historical source rules
	 * @param revision the immutable Git revision that selects their contents
	 * @param repositoryRelativeRulesRoot the source rules root relative to the repository
	 * @param bundleDirectory the initially absent destination below the work root
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules the other canonical rule paths required for this candidate
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical rule paths
	 * @return the published bundle location and immutable receipt
	 * @throws IOException if the destination is unsafe or the selected historical rules cannot form a valid bundle
	 */
	static ObjectNode materializeCandidateBundleFromGit(Path workRoot, Path repository, String revision,
		String repositoryRelativeRulesRoot, Path bundleDirectory, String primaryRule, List<String> additionalRules,
		Map<String, String> routeReplacements)
		throws IOException
	{
		Path work = workRoot.toAbsolutePath().normalize();
		Path bundle = bundleDirectory.toAbsolutePath().normalize();
		if (!bundle.startsWith(work) || bundle.equals(work))
			throw new IOException("Candidate bundle must remain beneath the CAT work root: " + bundle);
		CandidateBundle.Candidate candidate;
		ObjectNode receipt;
		try (ToolScope scope = new ToolScope())
		{
			candidate = CandidateBundle.materializeFromGit(scope.json(), repository, revision, repositoryRelativeRulesRoot,
				bundle, primaryRule, additionalRules, routeReplacements);
			receipt = CandidateBundle.receipt(scope.json(), candidate);
		}
		receipt.put("bundle", bundle.toString());
		return receipt;
	}

	/**
	 * Measures two candidate bundles under one fixed comparison contract without changing promotion state.
	 *
	 * <p>
	 * Each candidate receives the same suite trial number in alternating order. A benchmark deliberately keeps running
	 * after a prompt-behavior failure so both candidates receive the agreed evidence budget. A runner, validator, or
	 * execution-environment failure ends the operation because later trial outcomes would not be comparable.
	 *
	 * @param temporaryRoot the CAT work temp directory for short-lived build work
	 * @param suitePath the durable scenario suite
	 * @param baselineBundlePath the immutable rules for the pre-change candidate
	 * @param candidateBundlePath the immutable rules for the changed candidate
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent evidence directory for this comparison
	 * @param trialCount the equal number of whole-suite trials for each candidate
	 * @param maximumConcurrency the shared worker capacity
	 * @return both prepared records, their terminal decisions, and their shared comparison result
	 * @throws IOException if a prerequisite or benchmark batch cannot produce terminal evidence
	 */
	static ObjectNode benchmarkPair(Path temporaryRoot, Path suitePath, Path baselineBundlePath,
		Path candidateBundlePath, String harness, Path contextPath, Path runner, Path buildPlanPath,
		Path retainedDirectory, int trialCount, int maximumConcurrency) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return benchmarkPair(temporaryRoot, suitePath, baselineBundlePath, candidateBundlePath, harness, contextPath,
				runner, buildPlanPath, retainedDirectory, trialCount, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Measures both candidate bundles with the supplied typed evaluator runner.
	 *
	 * @param temporaryRoot the CAT work temp directory for short-lived build work
	 * @param suitePath the durable scenario suite
	 * @param baselineBundlePath the immutable baseline candidate bundle
	 * @param candidateBundlePath the immutable changed candidate bundle
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent evidence directory for this comparison
	 * @param trialCount the equal number of whole-suite trials for each candidate
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that retains executor and validator results
	 * @return both prepared records, their terminal decisions, and their shared comparison result
	 * @throws IOException if a prerequisite or benchmark batch cannot produce terminal evidence
	 */
	static ObjectNode benchmarkPair(Path temporaryRoot, Path suitePath, Path baselineBundlePath,
		Path candidateBundlePath, String harness, Path contextPath, Path runner, Path buildPlanPath,
		Path retainedDirectory, int trialCount, int maximumConcurrency, HarnessEvaluationRunner evaluationRunner)
		throws IOException
	{
		CandidateBundle.Candidate baselineCandidate;
		CandidateBundle.Candidate candidate;
		try (ToolScope scope = new ToolScope())
		{
			baselineCandidate = CandidateBundle.read(scope.json(), baselineBundlePath);
			candidate = CandidateBundle.read(scope.json(), candidateBundlePath);
		}
		validateEvaluationInputs(suitePath, baselineCandidate.primary().source(), harness, contextPath, runner);
		validateEvaluationInputs(suitePath, candidate.primary().source(), harness, contextPath, runner);
		Benchmark.Request request = new Benchmark.Request(temporaryRoot, suitePath,
			baselineCandidate,
			candidate, harness, contextPath, runner, buildPlanPath, retainedDirectory, trialCount, maximumConcurrency,
			evaluationRunner);
		try (ToolScope scope = new ToolScope())
		{
			return benchmark(scope.json()).execute(request);
		}
	}

	/**
	 * Supplies the evaluator operations that the paired-benchmark lifecycle schedules.
	 *
	 * @param json the mapper that owns paired-benchmark receipts
	 * @return the paired-benchmark owner with its narrow evaluator-execution collaboration
	 */
	private static Benchmark benchmark(JsonMapper json)
	{
		BenchmarkRecords records = benchmarkRecords();
		BenchmarkExecution execution = benchmarkExecution(json);
		return new Benchmark(json, new Benchmark.Operations()
		{
			@Override
			public void validateSingle(Benchmark.SingleRequest request) throws IOException
			{
				validateEvaluationInputs(request.suitePath(), request.candidate().primary().source(), request.harness(),
					request.contextPath(), request.runner());
			}

			@Override
			public ObjectNode prepareSingleAssembly(Benchmark.SingleRequest request) throws IOException
			{
				preflightCached(request.temporaryRoot(), request.suitePath());
				ObjectNode assembly = assemble(request.temporaryRoot(), request.buildPlanPath(), request.runner());
				RuntimeVerification.verifyLifecycleSoak(request.temporaryRoot(), assembly);
				return assembly;
			}

			@Override
			public ObjectNode prepareSingleRecord(Benchmark.SingleRequest request, ObjectNode assembly)
				throws IOException
			{
				ObjectNode prepared = prepareCandidate(request.temporaryRoot(), request.suitePath(), request.candidate(),
					request.harness(), request.contextPath(), request.runner(), assembly, request.retainedDirectory());
				Path run = path(prepared.path("run").asString());
				withRunLock(run, () ->
				{
					ObjectNode record = object(read(run), "prepared benchmark record");
					record.put("mode", "benchmark");
					write(run, record);
				});
				return prepared;
			}

			@Override
			public void bindSinglePlan(ObjectNode prepared) throws IOException
			{
				bindExecutionPlan(path(prepared.path("run").asString()),
					path(prepared.path("execution_plan").asString()));
			}

			@Override
			public ObjectNode runSingle(ObjectNode prepared, int maximumConcurrency,
				HarnessEvaluationRunner evaluationRunner) throws IOException
			{
				return runUntilTerminal(path(prepared.path("run").asString()),
					path(prepared.path("workspace").asString()), path(prepared.path("execution_plan").asString()),
					maximumConcurrency, evaluationRunner);
			}

			@Override
			public void completeSingle(ObjectNode prepared) throws IOException
			{
				Path run = path(prepared.path("run").asString());
				Timing.complete(json, run, () -> Decision.updateLocked(json, run, true, decisionAccess()));
			}

			@Override
			public ObjectNode prepareAssembly(Path temporaryRoot, Path suitePath, Path buildPlanPath, Path runner)
				throws IOException
			{
				preflightCached(temporaryRoot, suitePath);
				ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
				RuntimeVerification.verifyLifecycleSoak(temporaryRoot, assembly);
				return assembly;
			}

			@Override
			public ObjectNode existingRecord(Path runPath, CandidateBundle.Candidate candidate,
				int trialCount) throws IOException
			{
				return records.existing(runPath, candidate, trialCount);
			}

			@Override
			public ObjectNode prepareRecord(Benchmark.Request request,
				CandidateBundle.Candidate candidate, ObjectNode assembly, Path retainedDirectory)
				throws IOException
			{
				return records.prepare(request.temporaryRoot(), request.suitePath(), candidate, request.harness(),
					request.contextPath(), request.runner(), assembly, retainedDirectory, request.trialCount());
			}

			@Override
			public void executeRound(ObjectNode baseline, ObjectNode candidate, int firstTrial, int roundSize,
				Benchmark.Request request) throws IOException
			{
				Path baselineRun = path(baseline.path("run").asString());
				Path candidateRun = path(candidate.path("run").asString());
				execution.runFixedBenchmarkRound(baselineRun, path(baseline.path("workspace").asString()).resolve(
					"pair-" + firstTrial), path(baseline.path("execution_plan").asString()), candidateRun,
					path(candidate.path("workspace").asString()).resolve("pair-" + firstTrial),
					path(candidate.path("execution_plan").asString()), roundSize, request.maximumConcurrency(),
					request.evaluationRunner());
			}

			@Override
			public void failOnInfrastructure(Path baselineRun, Path candidateRun) throws IOException
			{
				execution.refuseInfrastructureFailure(baselineRun, candidateRun);
			}

			@Override
			public void recordInterruption(Path runPath, IOException failure)
			{
				execution.recordInterruption(runPath, failure);
			}

			@Override
			public void complete(Path runPath) throws IOException
			{
				Timing.complete(json, runPath, () -> Decision.updateLocked(json, runPath, true, decisionAccess()));
			}

			@Override
			public ObjectNode compare(Path baselineRun, Path candidateRun) throws IOException
			{
				return PromptEvaluation.compare(baselineRun, candidateRun, false);
			}
		});
	}

	/**
	 * Supplies the evaluator persistence operations used by paired benchmark records.
	 *
	 * @return the paired-benchmark record owner
	 */
	private static BenchmarkRecords benchmarkRecords()
	{
		return new BenchmarkRecords(new BenchmarkRecords.Access()
		{
			@Override
			public JsonNode read(Path runPath) throws IOException
			{
				return PromptEvaluation.read(runPath);
			}

			@Override
			public ObjectNode object(JsonNode value, String description) throws IOException
			{
				return PromptEvaluation.object(value, description);
			}

			@Override
			public ObjectNode object()
			{
				return newObject();
			}

			@Override
			public String text(ObjectNode record, String field, Path source) throws IOException
			{
				return PromptEvaluation.text(record, field, source);
			}

			@Override
			public void validateRunInputs(ObjectNode run, Path runPath) throws IOException
			{
				PromptEvaluation.validateRunInputs(run, runPath);
			}

			@Override
			public Path path(String value)
			{
				return PromptEvaluation.path(value);
			}

			@Override
			public void ensureFixedBenchmarkRun(Path runPath) throws IOException
			{
				PromptEvaluation.ensureFixedBenchmarkRun(runPath);
			}

			@Override
			public ObjectNode prepareCandidate(Path temporaryRoot, Path suitePath, CandidateBundle.Candidate candidate,
				String harness, Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory)
				throws IOException
			{
				return PromptEvaluation.prepareCandidate(temporaryRoot, suitePath, candidate, harness, contextPath, runner,
					assembly, retainedDirectory);
			}

			@Override
			public void withLock(Path runPath, BenchmarkRecords.IoOperation operation) throws IOException
			{
				withRunLock(runPath, operation::run);
			}

			@Override
			public void write(Path runPath, ObjectNode record) throws IOException
			{
				PromptEvaluation.write(runPath, record);
			}

			@Override
			public void bindExecutionPlan(Path runPath, Path planPath) throws IOException
			{
				PromptEvaluation.bindExecutionPlan(runPath, planPath);
			}
		});
	}

	/**
	 * Supplies evaluator operations used by the paired benchmark's live worker lifecycle.
	 *
	 * @param json the mapper that owns paired-benchmark worker records
	 * @return the paired-benchmark live-worker owner
	 */
	private static BenchmarkExecution benchmarkExecution(JsonMapper json)
	{
		return new BenchmarkExecution(new BenchmarkExecution.Access()
		{
			@Override
			public void validateMaximumConcurrency(int maximumConcurrency) throws IOException
			{
				PromptEvaluation.validateMaximumConcurrency(maximumConcurrency);
			}

			@Override
			public void bindExecutionPlan(Path runPath, Path planPath) throws IOException
			{
				PromptEvaluation.bindExecutionPlan(runPath, planPath);
			}

			@Override
			public void ensureFixedBenchmarkRun(Path runPath) throws IOException
			{
				PromptEvaluation.ensureFixedBenchmarkRun(runPath);
			}

			@Override
			public void bindMaximumConcurrency(Path runPath, int maximumConcurrency) throws IOException
			{
				PromptEvaluation.bindMaximumConcurrency(runPath, maximumConcurrency);
			}

			@Override
			public List<Integer> claimFixedBenchmarkTrials(Path runPath, int count) throws IOException
			{
				return PromptEvaluation.claimFixedBenchmarkTrials(runPath, count);
			}

			@Override
			public List<Integer> trialsIn(Path workspace) throws IOException
			{
				return PromptEvaluation.trialsIn(workspace);
			}

			@Override
			public boolean hasActiveEvaluationOwner(Path marker) throws IOException
			{
				return Lifecycle.hasActiveEvaluationOwner(marker);
			}

			@Override
			public ArrayNode workerRecords(Path workspace) throws IOException
			{
				return Lifecycle.workerRecords(workspace);
			}

			@Override
			public boolean hasNonterminalWorker(ArrayNode workers)
			{
				return Lifecycle.hasNonterminalWorker(workers);
			}

			@Override
			public boolean hasActiveWorkerOwner(ArrayNode workers)
			{
				return Lifecycle.hasActiveWorkerOwner(workers);
			}

			@Override
			public void terminalizeOwnerlessWorkers(Path workspace) throws IOException
			{
				Recovery.terminalizeOwnerlessWorkers(workspace);
			}

			@Override
			public JsonNode read(Path source) throws IOException
			{
				return PromptEvaluation.read(source);
			}

			@Override
			public ObjectNode object(JsonNode value, String description) throws IOException
			{
				return PromptEvaluation.object(value, description);
			}

			@Override
			public String text(ObjectNode value, String field, Path source) throws IOException
			{
				return PromptEvaluation.text(value, field, source);
			}

			@Override
			public Path path(String value)
			{
				return PromptEvaluation.path(value);
			}

			@Override
			public ArrayNode array(JsonNode value, String description, Path source) throws IOException
			{
				return PromptEvaluation.array(value, description, source);
			}

			@Override
			public List<Integer> unrecordedTrials(Path runPath, String caseId, List<Integer> trials)
				throws IOException
			{
				return PromptEvaluation.unrecordedTrials(runPath, caseId, trials);
			}

			@Override
			public void runFixedBenchmarkBatch(Path runPath, List<Integer> trials, Path workspace, Path planPath,
				int workerCapacity, HarnessEvaluationRunner evaluationRunner) throws IOException
			{
				WORKSPACE_LIFECYCLE.prepare(runPath, workspace);
				runOwnedNonCertifyingEvaluation(runPath, workspace, "benchmarking", () ->
				{
					Timing.start(json, runPath);
					return runClaimedBatch(runPath, trials, workspace, planPath, workerCapacity, false, evaluationRunner);
				});
			}

			@Override
			public boolean hasInfrastructureFailure(Path runPath) throws IOException
			{
				return PromptEvaluation.hasInfrastructureFailure(runPath);
			}
		});
	}

	/**
	 * Certifies a candidate only after its complete durable regression check promoted the exact same inputs.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param promotionPath the current successful promotion record
	 * @param suitePath the durable suite
	 * @param promptPath the promoted prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent formal-evaluation directory
	 * @param maximumConcurrency the shared worker capacity
	 * @return the prepared record and terminal formal decision
	 * @throws IOException if the candidate differs from the promotion or cannot reach a terminal decision
	 */
	static ObjectNode certifyPromoted(Path temporaryRoot, Path promotionPath, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return certifyPromoted(temporaryRoot, promotionPath, suitePath, promptPath, harness, contextPath, runner,
				buildPlanPath, retainedDirectory, maximumConcurrency, new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Certifies a promoted candidate with the supplied harness-evaluation runner.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param promotionPath the current successful promotion record
	 * @param suitePath the durable suite
	 * @param promptPath the promoted prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent formal-evaluation directory
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that executes evaluator requests
	 * @return the prepared record and terminal formal decision
	 * @throws IOException if the candidate differs from the promotion or cannot reach a terminal decision
	 */
	static ObjectNode certifyPromoted(Path temporaryRoot, Path promotionPath, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency,
		HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		promotionPath = promotionPath.toAbsolutePath().normalize();
		validateDurableCandidateStatePath(temporaryRoot, promotionPath);
		try (ToolScope scope = new ToolScope();
		CandidateController controller = CandidateController.start(scope.json(), CANDIDATE_ACCESS, promotionPath))
		{
			try
			{
				controller.identify(CANDIDATE_ACCESS, suitePath, promptPath, harness, contextPath, runner, buildPlanPath,
					maximumConcurrency);
				ObjectNode result = certifyPromotedOwned(temporaryRoot, promotionPath, suitePath, promptPath, harness,
					contextPath, runner, buildPlanPath, retainedDirectory, maximumConcurrency, evaluationRunner, controller);
				controller.complete();
				return result;
			}
			catch (IOException e)
			{
				controller.fail(e);
				throw e;
			}
		}
	}

	/**
	 * Certifies a candidate while its caller owns the complete candidate transition.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param promotionPath the current successful promotion record
	 * @param suitePath the durable suite
	 * @param promptPath the promoted prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent formal-evaluation directory
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that executes evaluator requests
	 * @param controller the active candidate-transition owner
	 * @return the prepared record and terminal formal decision
	 * @throws IOException if the candidate differs from the promotion or cannot reach a terminal decision
	 */
	private static ObjectNode certifyPromotedOwned(Path temporaryRoot, Path promotionPath, Path suitePath,
		Path promptPath, String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory,
		int maximumConcurrency, HarnessEvaluationRunner evaluationRunner, CandidateController controller) throws IOException
	{
		validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		CandidateState.verifyPromotionReceipt(promotionPath);
		CandidateState.verifyPromotedVisibleSuite(promotionPath, suitePath);
		controller.publish("assembling_runner");
		ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
		controller.publish("verifying_lifecycle");
		RuntimeVerification.verifyLifecycleSoak(temporaryRoot, assembly);
		controller.publish("verifying_visible_reviewer_controls");
		verifyVisibleReviewerControls(temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly,
			evaluationRunner);
		controller.publish("preflighting_formal_evaluation");
		preflightCached(temporaryRoot, suitePath);
		controller.publish("preparing_formal_evaluation");
		ObjectNode prepared = prepare(temporaryRoot, suitePath, promptPath, harness, contextPath, runner,
			assembly, retainedDirectory);
		Path run = path(prepared.path("run").asString());
		Path workspace = path(prepared.path("workspace").asString());
		Path plan = path(prepared.path("execution_plan").asString());
		bindExecutionPlan(run, plan);
		CandidateState.verifyPromotion(path(promotionPath.toString()), run);
		ObjectNode candidateState = object(read(promotionPath), "candidate state");
		List<String> uncovered = CandidateState.uncoveredLineageObligationKeys(candidateState,
			promotionPath);
		if (!uncovered.isEmpty())
		{
			throw new IOException("Formal certification requires every unresolved obligation to be covered by a later " +
				"regression check: " + String.join(", ", uncovered));
		}
		bindCandidateState(run, promotionPath);
		CandidateState.recordPendingCertification(promotionPath, run, workspace, plan);
		prepared.set("assembly", assembly);
		controller.publish("running_formal_evaluation");
		ObjectNode decision = runUntilTerminal(run, workspace, plan, maximumConcurrency, evaluationRunner);
		prepared.set("decision", decision);
		ObjectNode updatedCandidateState = recordCandidateFormalDecision(run, promotionPath,
			decision);
		if (updatedCandidateState != null)
			prepared.set("candidate_state", updatedCandidateState);
		return prepared;
	}

	/**
	 * Verifies controls that the suite declares before formal certification starts.
	 *
	 * <p>A failed control proves that the reviewer cannot reliably evaluate an existing scenario. This preflight uses a
	 * disposable suite record; a later full evaluation still verifies the
	 * materialized suite before it sends any task to a harness.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param suitePath the promoted visible suite
	 * @param promptPath the promoted prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent-validator contract
	 * @param runner the selected harness runner
	 * @param assembly the assembled runner identity
	 * @param evaluationRunner the runner that verifies visible semantic controls
	 * @throws IOException if visible-suite reviewer controls cannot establish their declared verdicts
	 */
	private static void verifyVisibleReviewerControls(Path temporaryRoot, Path suitePath, Path promptPath, String harness,
		Path contextPath, Path runner, ObjectNode assembly, HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path preflightDirectory = cleanup.add(InvocationDirectory.create(temporaryRoot,
				"prompt-evaluation-visible-reviewer-controls"));
			ObjectNode prepared = prepare(temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly,
				preflightDirectory.resolve("evaluation"));
			verifySemanticControls(path(prepared.path("run").asString()),
				path(prepared.path("workspace").asString()), path(prepared.path("execution_plan").asString()),
				evaluationRunner);
		}
	}

	/**
	 * Executes the only evaluator transition that candidate state permits for the supplied candidate inputs.
	 *
	 * <p>
	 * The controller owns routine state sequencing. It never chooses a prompt revision or diagnoses a failed
	 * requirement: when the retained state requires either judgment, it stops before starting a new harness turn and
	 * reports that prerequisite instead.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param candidateStatePath the durable candidate and lineage state
	 * @param suitePath the version-controlled suite
	 * @param promptPath the candidate prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent evaluation result directory
	 * @param maximumConcurrency the shared worker capacity
	 * @return the executed transition and its complete result
	 * @throws IOException if state requires a semantic repair or a retained promotion is incomplete
	 */
	static ObjectNode advance(Path temporaryRoot, Path candidateStatePath, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return advance(scope.json(), temporaryRoot, candidateStatePath, suitePath, promptPath, harness, contextPath,
				runner, buildPlanPath, retainedDirectory, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Executes the only evaluator transition that candidate state permits with the supplied evaluator runner.
	 *
	 * <p>
	 * The supplied runner lets focused tests provide retained evaluator evidence without starting a native process.
	 * Production callers use the overload that supplies the native runner.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param candidateStatePath the durable candidate and lineage state
	 * @param suitePath the version-controlled suite
	 * @param promptPath the candidate prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent evaluation result directory
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that produces typed evaluator evidence
	 * @return the executed transition and its complete result
	 * @throws IOException if state requires a semantic repair or a retained promotion is incomplete
	 */
	static ObjectNode advance(Path temporaryRoot, Path candidateStatePath, Path suitePath, Path promptPath,
		String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory, int maximumConcurrency,
		HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return advance(scope.json(), temporaryRoot, candidateStatePath, suitePath, promptPath, harness, contextPath,
				runner, buildPlanPath, retainedDirectory, maximumConcurrency, evaluationRunner);
		}
	}

	/**
	 * Executes the supplied candidate transition with its operation-owned mapper.
	 *
	 * @param json the operation's JSON mapper
	 * @param temporaryRoot the CAT work temp directory
	 * @param candidateStatePath the durable candidate and lineage state
	 * @param suitePath the version-controlled suite
	 * @param promptPath the candidate prompt artifact
	 * @param harness the selected harness
	 * @param contextPath the independent validator contract
	 * @param runner the selected harness runner
	 * @param buildPlanPath the runner build plan
	 * @param retainedDirectory the initially absent evaluation result directory
	 * @param maximumConcurrency the shared worker capacity
	 * @param evaluationRunner the runner that produces typed evaluator evidence
	 * @return the executed transition and its complete result
	 * @throws IOException if state requires a semantic repair or a retained promotion is incomplete
	 */
	private static ObjectNode advance(JsonMapper json, Path temporaryRoot, Path candidateStatePath, Path suitePath,
		Path promptPath, String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory,
		int maximumConcurrency, HarnessEvaluationRunner evaluationRunner) throws IOException
	{
		return candidateLifecycle(json).advance(new CandidateLifecycle.Request(temporaryRoot, candidateStatePath,
			suitePath, promptPath, harness, contextPath, runner, buildPlanPath, retainedDirectory, maximumConcurrency,
			evaluationRunner));
	}

	/**
	 * Resumes the exact formal evaluation that a previous controller prepared but did not terminalize.
	 *
	 * @param candidateStatePath the state that retains the pending formal evaluation
	 * @param maximumConcurrency the shared worker capacity
	 * @return the resumed evaluation receipt
	 * @throws IOException if the retained identity changed or cannot reach a terminal decision
	 */
	private static ObjectNode resumePendingCertification(Path candidateStatePath, int maximumConcurrency)
		throws IOException
	{
		Path normalizedState = candidateStatePath.toAbsolutePath().normalize();
		ObjectNode state = object(read(normalizedState), "candidate state");
		ObjectNode pending = object(state.path("pending_certification"), "pending certification");
		Path runPath = path(text(pending, "run", normalizedState));
		Path workspacePath = path(text(pending, "workspace", normalizedState));
		Path planPath = path(text(pending, "execution_plan", normalizedState));
		verifyFingerprint(planPath, text(pending, "execution_plan_sha256", normalizedState),
			"Pending formal execution plan changed before resumption");
		CandidateState.verifyPendingCertificationIdentity(pending, runPath, normalizedState);
		CandidateState.verifyPromotion(normalizedState, runPath);
		ObjectNode result = newObject();
		result.put("run", runPath.toString());
		result.put("workspace", workspacePath.toString());
		result.put("execution_plan", planPath.toString());
		ObjectNode decision = runUntilTerminal(runPath, workspacePath, planPath, maximumConcurrency);
		result.set("decision", decision);
		ObjectNode candidateState = recordCandidateFormalDecision(runPath, normalizedState,
			decision);
		if (candidateState != null)
			result.set("candidate_state", candidateState);
		return result;
	}

	/**
	 * Creates and executes one non-certifying smoke run through the complete evaluation preparation transaction.
	 *
	 * <p>
	 * The evaluator uses the workspace returned by preparation. Its ownership marker is the only pre-existing file;
	 * the smoke operation verifies that marker before adding its retained trial files.
	 *
	 * @param temporaryRoot the CAT work temp directory for short-lived build diagnostics
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the version-controlled validator context
	 * @param runner the selected harness runner
	 * @param buildPlanPath the selected project-owned plan that produces the runner artifact
	 * @param caseId the one discriminating testcase to execute
	 * @param retainedDirectory the initially absent run-record directory below the CAT work temp directory
	 * @param maximumConcurrency the shared worker capacity
	 * @return the prepared paths, assembly identity, and non-certifying smoke result
	 * @throws IOException if preparation or the selected testcase cannot produce terminal evidence
	 */
	static ObjectNode smokeEvaluate(Path temporaryRoot, Path suitePath, Path promptPath, String harness, Path contextPath,
		Path runner, Path buildPlanPath, String caseId, Path retainedDirectory, int maximumConcurrency)
		throws IOException
	{
		validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		preflightCached(temporaryRoot, suitePath);
		ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
		ObjectNode prepared = prepare(temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly,
			retainedDirectory);
		Path run = path(prepared.path("run").asString());
		Path workspace = path(prepared.path("workspace").asString());
		Path plan = path(prepared.path("execution_plan").asString());
		ObjectNode smoke = smoke(run, caseId, workspace, plan, maximumConcurrency);
		prepared.set("assembly", assembly);
		prepared.set("smoke", smoke);
		return prepared;
	}

	/**
	 * Creates and executes a routing-only diagnostic after a recorded end-to-end routing failure.
	 *
	 * <p>
	 * The generated matrix exists only while the evaluator prepares and runs it. Preparation snapshots the complete
	 * matrix beside the retained run record, so the terminal evidence preserves the exact diagnostic without leaving an
	 * unowned manifest in CAT work storage. The operation therefore gives a public caller one complete result
	 * instead of requiring it to invoke internal preparation and execution steps.
	 *
	 * @param json the operation's JSON mapper
	 * @param temporaryRoot the CAT work temp directory for the generated suite and retained diagnostic evidence
	 * @param sourceRunPath the retained organic failure that established a routing/delivery problem
	 * @param caseId the failed testcase in the source run
	 * @param trial the failed whole-prompt trial in the source run
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner
	 * @param buildPlanPath the selected project-owned plan that produces the runner artifact
	 * @param retainedDirectory the initially absent CAT work temp directory that holds completed
	 *        diagnostic evidence
	 * @param maximumConcurrency the shared worker capacity
	 * @return the prepared paths, assembly identity, and terminal routing-matrix result
	 * @throws IOException if the source evidence, generated matrix, or terminal diagnostic evidence is invalid
	 */
	static ObjectNode routingDiagnostic(JsonMapper json, Path temporaryRoot, Path sourceRunPath, String caseId,
		int trial,
		Path promptPath, String harness, Path contextPath, Path runner, Path buildPlanPath, Path retainedDirectory,
		int maximumConcurrency) throws IOException
	{
		Path certificationSuite = Diagnostics.requireSource(diagnostics(json), sourceRunPath, caseId, trial,
			"routing-only", FailureClass.ROUTING_DELIVERY, true);
		validateEvaluationInputs(certificationSuite, promptPath, harness, contextPath, runner);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path generatedDirectory = cleanup.add(InvocationDirectory.create(temporaryRoot,
				"prompt-evaluation-routing-diagnostic"));
			Path diagnosticSuite = generatedDirectory.resolve("routing-diagnostic");
			createRoutingDiagnostic(json, sourceRunPath, caseId, trial, diagnosticSuite);
			preflightCached(temporaryRoot, diagnosticSuite);
			ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
			ObjectNode prepared = prepare(temporaryRoot, diagnosticSuite, promptPath, harness, contextPath, runner,
				assembly, retainedDirectory);
			Path run = path(prepared.path("run").asString());
			Path workspace = path(prepared.path("workspace").asString());
			Path plan = path(prepared.path("execution_plan").asString());
			ObjectNode matrix = runRoutingMatrix(run, workspace, plan, maximumConcurrency);
			prepared.set("assembly", assembly);
			prepared.set("routing_matrix", matrix);
			return prepared;
		}
	}

	/**
	 * Runs three matched non-certifying trials with a selected executor profile while retaining the normal independent
	 * validator profile.
	 *
	 * <p>
	 * This command answers only whether a repeated, already localized failure changes when the executor profile
	 * changes. It does not certify the prompt or establish a minimum capable profile.
	 *
	 * @param json the operation's JSON mapper
	 * @param temporaryRoot the CAT work temp directory for the evaluation evidence
	 * @param sourceRunPath the retained unresolved organic failure that justifies this experiment
	 * @param caseId the failed testcase in the source run
	 * @param trial the failed whole-prompt trial in the source run
	 * @param suitePath a generated one-case direct-application or routing-only diagnostic suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner
	 * @param buildPlanPath the selected build plan for the runner artifact
	 * @param executorModel the diagnostic executor model
	 * @param executorEffort the diagnostic executor reasoning effort
	 * @param retainedDirectory the initially absent evidence directory below the CAT work temp directory
	 * @param maximumConcurrency the shared worker capacity
	 * @return the prepared paths, assembly identity, and three-trial diagnostic result
	 * @throws IOException if the diagnostic inputs or retained trial evidence are invalid
	 */
	private static ObjectNode capabilityDiagnostic(JsonMapper json, Path temporaryRoot, Path sourceRunPath,
		String caseId, int trial, Path suitePath, Path promptPath, String harness, Path contextPath, Path runner,
		Path buildPlanPath,
		String executorModel, String executorEffort, Path retainedDirectory, int maximumConcurrency) throws IOException
	{
		requireThat(executorModel, "executorModel").isNotBlank();
		requireThat(executorEffort, "executorEffort").isNotBlank();
		validateEvaluationInputs(suitePath, promptPath, harness, contextPath, runner);
		CapabilityDiagnostic diagnostic = capabilityDiagnostic(json);
		diagnostic.validateSuite(suitePath);
		requireCapabilityDiagnosticSource(sourceRunPath,
			Diagnostics.sourceCertificationSuite(diagnostics(json), suitePath), caseId, trial,
			SuiteContract.diagnosticKind(SuiteContract.read(suitePath), suitePath, SuitePurpose.DIAGNOSTIC));
		preflightCached(temporaryRoot, suitePath);
		ObjectNode assembly = assemble(temporaryRoot, buildPlanPath, runner);
		Profile validatorProfile = ExecutionPlan.resolveRunnerProfile(runner, temporaryRoot);
		Profile diagnosticExecutorProfile = new Profile(validatorProfile.owner(), executorModel,
			executorEffort);
		ObjectNode prepared = prepare(temporaryRoot, suitePath, promptPath, harness, contextPath, runner, assembly,
			retainedDirectory, diagnosticExecutorProfile, validatorProfile, null);
		Path run = path(prepared.path("run").asString());
		Path workspace = path(prepared.path("workspace").asString());
		Path plan = path(prepared.path("execution_plan").asString());
		diagnostic.start(run, new CapabilityDiagnostic.Profile(validatorProfile.label()),
			new CapabilityDiagnostic.Profile(diagnosticExecutorProfile.label()));
		ObjectNode result = diagnostic.run(run, workspace, plan, maximumConcurrency,
			new NativeHarnessEvaluationRunner(json));
		prepared.set("assembly", assembly);
		prepared.set("capability_diagnostic", result);
		return prepared;
	}

	/**
	 * Runs one selected, version-controlled build plan and verifies the artifact the selected runner will install.
	 *
	 * @param temporaryRoot the CAT work temp directory for short-lived build diagnostics
	 * @param buildPlanPath the explicit project-owned build-plan file
	 * @param runner the selected harness runner that validates the produced artifact
	 * @return the verified artifact identity produced by the plan
	 * @throws IOException if the plan, command, or produced artifact is invalid
	 */
	static ObjectNode assemble(Path temporaryRoot, Path buildPlanPath, Path runner) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Assembly.assemble(scope.json(), temporaryRoot, buildPlanPath, runner);
		}
	}

	/**
	 * Verifies that a build command preserved the immutable inputs its receipt will later use to decide reuse.
	 *
	 * @param planPath the build plan that declares the inputs
	 * @param workingDirectory the base directory for declared inputs
	 * @param inputs all declared build inputs
	 * @param inputFingerprint the expected full input fingerprint
	 * @param executionInputs the declared inputs that define an evaluation environment
	 * @param executionInputFingerprint the expected evaluation-environment input fingerprint
	 * @throws IOException if the command changed a declared input
	 */
	public static void verifyUnchangedBuildInputs(Path planPath, Path workingDirectory, List<Path> inputs,
		String inputFingerprint, List<Path> executionInputs, String executionInputFingerprint) throws IOException
	{
		if (!inputFingerprint.equals(fingerprintBuildInputs(workingDirectory, inputs)))
			throw new IOException("Build modified a declared immutable input: " + planPath);
		if (!executionInputFingerprint.equals(fingerprintBuildInputs(workingDirectory, executionInputs)))
		{
			throw new IOException("Build modified an execution input used to compare prompt-compliance results: " +
				planPath);
		}
	}

	/**
	 * Writes the verified identity of one complete runtime assembly or one prompt-only package update.
	 *
	 * @param temporaryRoot the CAT work directory that owns transient runner-query output
	 * @param receipt the bounded reusable receipt to replace
	 * @param planPath the build plan used for this assembly
	 * @param planFingerprint the build-plan fingerprint
	 * @param inputFingerprint the complete declared-input fingerprint
	 * @param executionInputFingerprint the runtime-only input fingerprint
	 * @param runnerFingerprint the selected runner launcher fingerprint
	 * @param workingDirectory the declared build directory
	 * @param declaredArtifact the rebuilt or repackaged plugin artifact
	 * @param runner the selected runner that verifies the artifact
	 * @param repackagedPromptBundle whether only model-facing prompt files were repackaged
	 * @return the complete reusable assembly identity
	 * @throws IOException if the selected runner rejects the artifact
	 */
	public static ObjectNode writeAssemblyReceipt(Path temporaryRoot, Path receipt, Path planPath,
		String planFingerprint, String inputFingerprint, String executionInputFingerprint, String runnerFingerprint,
		Path workingDirectory, Path declaredArtifact, Path runner, boolean repackagedPromptBundle) throws IOException
	{
		Path artifact = ExecutionPlan.verifyRunnerArtifact(runner, temporaryRoot, declaredArtifact);
		ObjectNode result = newObject();
		result.put("build_plan", planPath.toString());
		result.put("build_plan_sha256", planFingerprint);
		result.put("build_inputs_sha256", inputFingerprint);
		result.put("execution_inputs_sha256", executionInputFingerprint);
		result.put("runner_sha256", runnerFingerprint);
		result.put("working_directory", workingDirectory.toString());
		result.put("runner_artifact", artifact.toString());
		result.put("runner_artifact_sha256", fingerprintArtifact(artifact));
		result.put("prompt_bundle_sha256", fingerprintPromptBundle(artifact));
		result.put("reused", false);
		result.put("repackaged_prompt_bundle", repackagedPromptBundle);
		ObjectNode receiptContents = result.deepCopy();
		write(receipt, receiptContents);
		AssemblyCache.prune(receipt.getParent(), receipt);
		return result;
	}

	/**
	 * Marks an evaluation record directory as owned by its retained run record.
	 *
	 * @param recordDirectory the evaluation evidence directory
	 * @param runPath the retained run record
	 * @throws IOException if the ownership marker cannot be written
	 */
	public static void markOwnedRecord(Path recordDirectory, Path runPath) throws IOException
	{
		ArtifactRetention.markRecord(recordDirectory, runPath);
	}

	/**
	 * Determines whether the prior receipt proves that the runtime is unchanged even though prompt inputs changed.
	 *
	 * @param temporaryRoot the CAT work directory that owns transient runner-query output
	 * @param receipt the cached assembly receipt
	 * @param planFingerprint the current build-plan fingerprint
	 * @param executionInputFingerprint the current runtime input fingerprint
	 * @param runnerFingerprint the selected runner launcher fingerprint
	 * @param declaredArtifact the artifact that a prompt package update would modify
	 * @param runner the runner that verifies the unchanged runtime
	 * @return {@code true} if a prompt-only package command may update the artifact
	 * @throws IOException if the runner cannot verify an otherwise matching artifact
	 */
	public static boolean canRepackagePromptBundle(Path temporaryRoot, Path receipt, String planFingerprint,
		String executionInputFingerprint, String runnerFingerprint, Path declaredArtifact, Path runner) throws IOException
	{
		return AssemblyCache.canRepackage(temporaryRoot, receipt, planFingerprint, executionInputFingerprint,
			runnerFingerprint, declaredArtifact, runner);
	}

	/**
	 * Returns the bounded time a declared build may own an evaluator before the evaluator records a terminal failure.
	 *
	 * @param plan the selected build plan
	 * @param planPath the plan used for diagnostics
	 * @return the declared duration, or the safe default for older focused fixtures
	 * @throws IOException if the declared duration is not a positive whole number of seconds
	 */
	public static Duration buildTimeout(ObjectNode plan, Path planPath) throws IOException
	{
		return BuildInputs.timeout(plan, planPath);
	}

	/**
	 * Returns the declared inputs whose unchanged contents permit a prior runner assembly to be reused.
	 *
	 * @param plan the selected build plan
	 * @param field the plan field that declares this input set
	 * @param planPath the plan used for diagnostics
	 * @param workingDirectory the base for every declared input
	 * @param runnerArtifact the artifact the build produces
	 * @param fallback the input set to use when non-production test plans omit {@code field}, or {@code null} to require
	 *        its declaration
	 * @return the normalized declared input paths
	 * @throws IOException if the plan does not declare bounded existing inputs
	 */
	public static List<Path> buildInputs(ObjectNode plan, String field, Path planPath, Path workingDirectory,
		Path runnerArtifact, List<Path> fallback)
		throws IOException
	{
		return BuildInputs.paths(plan, field, planPath, workingDirectory, runnerArtifact, fallback);
	}

	/**
	 * Fingerprints all declared build inputs without guessing which source files a command might read.
	 *
	 * @param workingDirectory the declared input base
	 * @param inputs the validated build inputs
	 * @return the stable declared-input fingerprint
	 * @throws IOException if an input cannot be read
	 */
	public static String fingerprintBuildInputs(Path workingDirectory, List<Path> inputs) throws IOException
	{
		return BuildInputs.fingerprint(workingDirectory, inputs);
	}

	/**
	 * Returns the CAT work root that owns durable evaluator state for the supplied CAT work temp directory.
	 *
	 * <p>
	 * Callers receive the temp directory from {@link ToolScope#temporaryWorkRoot()}, whose parent is the work root.
	 * Keeping the derivation here prevents cached receipts and immutable artifacts from being placed in the subtree that
	 * invocation cleanup removes.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @return the CAT work root
	 * @throws IOException if the CAT work temp directory has no parent
	 */
	public static Path workRootForTemporaryResources(Path temporaryRoot) throws IOException
	{
		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path work = temporary.getParent();
		if (work == null)
			throw new IOException("CAT work temp directory has no CAT work root: " + temporary);
		return work;
	}

	/**
	 * Rejects a candidate-state location whose owning invocation cleanup can delete it.
	 *
	 * <p>
	 * Candidate state determines later legal transitions and can outlive any individual check or certification run. It
	 * therefore belongs below the CAT work root but outside its invocation-local {@code temp} directory. Without this
	 * boundary check, finalizing a completed workspace can remove the controller state that a later transition needs.
	 *
	 * @param temporaryRoot the CAT work temp directory for the current invocation
	 * @param candidateStatePath the durable controller-state path
	 * @throws IOException if the state is outside CAT work or inside invocation-local storage
	 */
	static void validateDurableCandidateStatePath(Path temporaryRoot, Path candidateStatePath) throws IOException
	{
		requireThat(temporaryRoot, "temporaryRoot").isNotNull();
		requireThat(candidateStatePath, "candidateStatePath").isNotNull();
		Path temporary = temporaryRoot.toAbsolutePath().normalize();
		Path work = workRootForTemporaryResources(temporary);
		Path state = candidateStatePath.toAbsolutePath().normalize();
		if (!state.startsWith(work) || state.startsWith(temporary))
		{
			throw new IOException("Candidate state must be retained below CAT work outside invocation-local temp: " + state);
		}
	}

	/**
	 * Reads an assembly receipt only when its immutable inputs and verified artifact still match the current request.
	 *
	 * @param json the assembly operation's JSON mapper
	 * @param temporaryRoot the CAT work directory that owns transient runner-query output
	 * @param receipt the receipt to inspect
	 * @param planFingerprint the current plan fingerprint
	 * @param inputFingerprint the current declared-input fingerprint
	 * @param runnerFingerprint the current runner fingerprint
	 * @param declaredArtifact the artifact the plan expects
	 * @param runner the runner that verifies the artifact
	 * @return a reusable result, or {@code null} when a rebuild is required
	 * @throws IOException if a matching receipt cannot be verified
	 */
	public static ObjectNode readReusableAssembly(JsonMapper json, Path temporaryRoot, Path receipt,
		String planFingerprint,
		String inputFingerprint, String runnerFingerprint, Path declaredArtifact, Path runner) throws IOException
	{
		return AssemblyCache.reusable(json, temporaryRoot, receipt, planFingerprint, inputFingerprint,
			runnerFingerprint, declaredArtifact, runner);
	}

	/**
	 * Fingerprints installed model-facing resources while keeping protocol-only test runners independent of an unrelated
	 * prompt bundle.
	 *
	 * @param artifact the selected runner artifact
	 * @return the installed prompt-bundle fingerprint, or an explicit absence marker for a non-packaging fixture
	 * @throws IOException if an installed prompt bundle cannot be hashed
	 */
	public static String fingerprintPromptBundle(Path artifact) throws IOException
	{
		List<Path> promptRoots = List.of("agents", "skills", "rules").stream().map(artifact::resolve).
			filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).toList();
		if (promptRoots.isEmpty())
			return "not-packaged";
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Path promptRoot : promptRoots)
				updateFixtureDigest(digest, "prompt/" + artifact.relativize(promptRoot), promptRoot);
			return java.util.HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Runs a deterministic suite preflight once for each immutable suite fingerprint.
	 *
	 * <p>
	 * The check controls are expensive enough to matter during prompt iteration, but a successful result is reusable
	 * only while every suite input that can change a check result is unchanged. The fingerprint and receipt make that
	 * boundary explicit instead of skipping preflight because a previous command happened to succeed.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns reusable transient receipts
	 * @param suitePath the version-controlled test suite
	 * @throws IOException if preflight cannot establish that the deterministic controls work
	 */
	private static void preflightCached(Path temporaryRoot, Path suitePath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			Preflight.preflightCached(scope.json(), temporaryRoot, suitePath);
		}
	}

	/**
	 * Resolves a relative path from its plan-declared base.
	 *
	 * @param base the directory relative paths may use
	 * @param value the declared relative path
	 * @param description the reader-visible value being resolved
	 * @return the normalized declared path
	 * @throws IOException if the declaration is absolute
	 */
	public static Path planPath(Path base, String value, String description) throws IOException
	{
		Path relative = Path.of(value);
		if (relative.isAbsolute())
			throw new IOException(description + " must be relative to its declared build-plan base: " + value);
		return base.resolve(relative).normalize();
	}

	/**
	 * Retains or removes terminal evidence with the operation-owned JSON mapper.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param temporaryRoot the CAT work temp directory
	 * @param workRoot the CAT work root
	 * @param runPath the persistent terminal result record
	 * @param workspaceRoot the evaluator-owned worker workspace
	 * @param action the requested evidence lifecycle action
	 * @param durableDirectory the destination for {@code retain}, or {@code null} for {@code delete}
	 * @return the verified report and the resulting evidence location or deleted roots
	 * @throws IOException if the evaluation is live, evidence is invalid, or the requested action is malformed
	 */
	static ObjectNode finalizeEvaluation(JsonMapper json, Path temporaryRoot, Path workRoot, Path runPath,
		Path workspaceRoot, String action, Path durableDirectory) throws IOException
	{
		return evidenceFinalization(json).finalizeEvaluation(json, temporaryRoot, workRoot, runPath, workspaceRoot,
			action, durableDirectory);
	}

	/**
	 * Retains or removes the complete evidence for one terminal evaluation.
	 *
	 * @param temporaryRoot the CAT work temp directory
	 * @param workRoot the CAT work root
	 * @param runPath the persistent terminal result record
	 * @param workspaceRoot the evaluator-owned worker workspace
	 * @param action the requested evidence lifecycle action
	 * @param durableDirectory the destination for {@code retain}, or {@code null} for {@code delete}
	 * @return the verified report and the resulting evidence location or deleted roots
	 * @throws IOException if the evaluation is live, evidence is invalid, or the requested action is malformed
	 */
	static ObjectNode finalizeEvaluation(Path temporaryRoot, Path workRoot, Path runPath, Path workspaceRoot,
		String action, Path durableDirectory)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return finalizeEvaluation(scope.json(), temporaryRoot, workRoot, runPath, workspaceRoot, action,
				durableDirectory);
		}
	}

	/**
	 * Selects the durable location for a retained terminal record when the caller has no workflow-specific destination.
	 *
	 * <p>
	 * Finalization must not leave evidence under invocation-local storage merely because a caller cannot name a durable
	 * directory. The original absolute record path is unique while the record is active, and its digest produces a
	 * stable directory name without exposing the path's syntax as durable workflow semantics.
	 *
	 * @param workRoot the CAT work root that owns durable evidence
	 * @param runPath the record being retained
	 * @return the initially absent deterministic destination
	 * @throws IOException if the path digest cannot be computed
	 */
	private static Path defaultRetainedEvidenceDirectory(Path workRoot, Path runPath) throws IOException
	{
		return workRoot.resolve("prompt-evaluation-retained").resolve(sha256(runPath.toString()));
	}

	/**
	 * Counts regular-file bytes below an owned evidence directory without following links.
	 *
	 * <p>
	 * The finalization receipt reports the concrete space reclaimed or retained. Counting before deletion keeps the
	 * result stable even though the directory no longer exists when the caller reads the receipt.
	 *
	 * @param root the owned evidence root
	 * @return the total size of regular files below the root
	 * @throws IOException if an evidence entry cannot be inspected
	 */
	public static long fileTreeBytes(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			long result = 0;
			for (Path path : paths.toList())
				if (Files.isRegularFile(path, NOFOLLOW_LINKS))
					result = Math.addExact(result, Files.size(path));
			return result;
		}
	}

	/**
	 * Prepares the bounded independent review required when a failure recurs or related failures identify one obligation.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns the review bundle
	 * @param candidateStatePath the candidate state containing unresolved failure signatures
	 * @param sourceRunPath the retained check that observed those failures
	 * @param bundleDirectory the initially absent directory for the review input and result
	 * @return the sealed review-bundle receipt and the signature keys it must address
	 * @throws IOException if recurrence evidence, review inputs, or the output location is invalid
	 */
	static ObjectNode prepareRecurrenceReview(Path temporaryRoot, Path candidateStatePath, Path sourceRunPath,
		Path bundleDirectory) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return RecurrenceReview.prepare(scope.json(), temporaryRoot, candidateStatePath, sourceRunPath,
				bundleDirectory);
		}
	}

	/**
	 * Records the structured result of a prepared repeated-failure review.
	 *
	 * @param candidateStatePath the candidate state whose pending review is completed
	 * @param bundleDirectory the prepared review bundle
	 * @param resultPath the reviewer's structured conclusion inside that bundle
	 * @return the completed review record
	 * @throws IOException if the result is incomplete or does not address every repeated signature
	 */
	static ObjectNode recordRecurrenceReview(Path candidateStatePath, Path bundleDirectory, Path resultPath)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return RecurrenceReview.record(scope.json(), candidateStatePath, bundleDirectory, resultPath);
		}
	}


	/**
	 * Creates every deterministic artifact needed before an evaluation starts.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns the evaluation record and its fresh workspace
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the version-controlled independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param runnerArtifact the rebuilt harness artifact that runner will use
	 * @param retainedDirectory the initially absent CAT work temp directory that holds complete evaluation evidence
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	static ObjectNode prepare(Path temporaryRoot, Path suitePath, Path promptPath, String harness, Path contextPath,
		Path runner, Path runnerArtifact, Path retainedDirectory) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Preparation.direct(scope.json(), temporaryRoot, suitePath, promptPath, harness, contextPath, runner,
				runnerArtifact, retainedDirectory);
		}
	}

	/**
	 * Creates one ordinary evaluation record from a verified build assembly.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns the evaluation record and fresh workspace
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param assembly the verified build inputs and runner artifact
	 * @param retainedDirectory the initially absent evidence directory below the CAT work temp directory
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	private static ObjectNode prepare(Path temporaryRoot, Path suitePath, Path promptPath, String harness,
		Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Preparation.assembled(scope.json(), temporaryRoot, suitePath, promptPath, harness, contextPath,
				runner, assembly, retainedDirectory);
		}
	}

	/**
	 * Creates one evaluation record whose runner receives the exact rule files in an immutable candidate bundle.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns the evaluation record and fresh workspace
	 * @param suitePath the version-controlled test suite
	 * @param candidate the rules being measured at their installed paths
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param assembly the verified build inputs and runner artifact selected for this evaluation
	 * @param retainedDirectory the initially absent evidence directory below the CAT work temp directory
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	private static ObjectNode prepareCandidate(Path temporaryRoot, Path suitePath,
		CandidateBundle.Candidate candidate, String harness, Path contextPath, Path runner,
		ObjectNode assembly, Path retainedDirectory) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Preparation.candidate(scope.json(), temporaryRoot, suitePath, candidate, harness, contextPath, runner,
				assembly, retainedDirectory);
		}
	}

	/**
	 * Creates one evaluation record with independently selected executor and validator profiles.
	 *
	 * @param temporaryRoot the CAT work temp directory that owns the evaluation record and fresh workspace
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the independent-validator context
	 * @param runner the selected harness runner launcher
	 * @param assembly the verified build inputs and runner artifact selected for this evaluation
	 * @param retainedDirectory the initially absent CAT work temp directory that holds complete evaluation evidence
	 * @param executorProfile the profile that performs task work
	 * @param validatorProfile the profile that independently reviews task evidence
	 * @param candidate the optional immutable rule bundle installed for this evaluation
	 * @return the workspace, plan, and run record that form one ready evaluation
	 * @throws IOException if a prerequisite cannot be verified or an owned artifact cannot be created
	 */
	private static ObjectNode prepare(Path temporaryRoot, Path suitePath, Path promptPath, String harness,
		Path contextPath, Path runner, ObjectNode assembly, Path retainedDirectory, Profile executorProfile,
		Profile validatorProfile, CandidateBundle.Candidate candidate) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Preparation.create(scope.json(), temporaryRoot, suitePath, promptPath, harness, contextPath, runner,
				assembly, retainedDirectory, executorProfile, validatorProfile, candidate);
		}
	}

	/**
	 * Validates every evaluation input that can fail before building a harness artifact.
	 *
	 * @param suitePath the version-controlled test suite
	 * @param promptPath the prompt artifact under test
	 * @param harness the selected harness identifier
	 * @param contextPath the version-controlled validator context
	 * @param runner the selected harness runner
	 * @throws IOException if an input is absent or malformed
	 */
	public static void validateEvaluationInputs(Path suitePath, Path promptPath, String harness, Path contextPath,
		Path runner) throws IOException
	{
		SuiteContract.validate(suitePath);
		if (!Files.isRegularFile(promptPath))
			throw new IOException("Expected prompt artifact: " + promptPath);
		if (SuiteContract.read(suitePath).has("generalization"))
			Generalization.audit(suitePath, promptPath);
		if (harness.isBlank())
			throw new IOException("Expected non-blank harness identifier");
		if (!Files.isRegularFile(contextPath))
			throw new IOException("Expected execution context: " + contextPath);
		if (!Files.isRegularFile(runner) || !Files.isExecutable(runner))
			throw new IOException("Expected executable harness runner: " + runner);
	}

	/**
	 * Reports the evaluator's live workers using the phase that each runner recorded in its owned manifest.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace that may contain live worker runs
	 * @param summary whether to omit the complete retained-worker listing
	 * @return the observable evaluator and worker activity
	 * @throws IOException if the record or owned workspace cannot be read
	 */
	static ObjectNode status(Path runPath, Path workspaceRoot, boolean summary) throws IOException
	{
		return Lifecycle.status(runPath, workspaceRoot, summary);
	}
	/**
	 * Parses an optional shared harness-concurrency limit.
	 *
	 * @param args the command-line arguments
	 * @param mandatoryLength the argument count before the optional limit
	 * @return the requested limit, or the shared default
	 */
	private static int maximumConcurrency(String[] args, int mandatoryLength)
	{
		if (args.length == mandatoryLength)
			return HarnessConcurrencyLimit.DEFAULT_MAXIMUM_CONCURRENCY;
		if (!args[mandatoryLength].equals("--max-concurrency"))
			throw new IllegalArgumentException("Expected --max-concurrency");
		return Integer.parseInt(args[mandatoryLength + 1]);
	}

	/**
	 * Retains the capacity that owns a terminal evaluation so a replacement evaluator does not silently change it.
	 *
	 * @param runPath the persistent evaluation record
	 * @param maximumConcurrency the shared worker capacity selected for this evaluation
	 * @throws IOException if an earlier owner selected a different capacity
	 */
	private static void bindMaximumConcurrency(Path runPath, int maximumConcurrency) throws IOException
	{
		withRunLock(runPath, () ->
		{
			ObjectNode run = object(read(runPath), "run");
			if (run.has("maximum_concurrency"))
			{
				if (run.path("maximum_concurrency").asInt(-1) != maximumConcurrency)
					throw new IOException("Evaluation concurrency differs from the recorded value: " + maximumConcurrency);
				return;
			}
			run.put("maximum_concurrency", maximumConcurrency);
			write(runPath, run);
		});
	}

	/**
	 * Returns the capacity retained by the first evaluator, or the documented default for a legacy prepared record.
	 *
	 * @param run the persistent evaluation record
	 * @param runPath the evaluation-record path used in a malformed-record diagnostic
	 * @return the capacity a replacement evaluator must use
	 * @throws IOException if the retained capacity is outside the shared supported range
	 */
	public static int recordedMaximumConcurrency(ObjectNode run, Path runPath) throws IOException
	{
		int result = run.path("maximum_concurrency").asInt(HarnessConcurrencyLimit.DEFAULT_MAXIMUM_CONCURRENCY);
		if (result < 1 || result > HarnessConcurrencyLimit.DEFAULT_MAXIMUM_CONCURRENCY)
			throw new IOException("Evaluation record has an invalid maximum_concurrency: " + runPath);
		return result;
	}

	/**
	 * Runs one claimed testcase through the declared executor and validator commands and records its terminal state.
	 *
	 * @param runPath the persistent evaluation record
	 * @param caseId the declared testcase identifier
	 * @param trial the next claimed whole-prompt trial number
	 * @param workspaceRoot an initially empty owned workspace root
	 * @param planPath the typed executor and validator command plan
	 * @param output the destination for the serialized terminal result
	 * @throws IOException if the transaction cannot be prepared or its terminal evidence cannot be recorded
	 */
	private static void execute(Path runPath, String caseId, int trial, Path workspaceRoot, Path planPath,
		Consumer<String> output)
		throws IOException
	{
		TrialClaims.reserveExact(runPath, trial);
		ObjectNode result = executeClaimed(runPath, caseId, trial, workspaceRoot, planPath);
		output.accept(jsonText(result));
	}

	/**
	 * Runs a previously claimed testcase and records its terminal outcome.
	 *
	 * @param runPath the persistent evaluation record
	 * @param caseId the declared testcase identifier
	 * @param trial the already claimed whole-prompt trial number
	 * @param workspaceRoot the initially absent owned workspace root
	 * @param planPath the typed executor and validator command plan
	 * @return the terminal status and retained evidence paths
	 * @throws IOException if the transaction cannot be prepared or its terminal evidence cannot be recorded
	 */
	private static ObjectNode executeClaimed(Path runPath, String caseId, int trial, Path workspaceRoot, Path planPath)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return executeClaimed(scope.json(), runPath, caseId, trial, workspaceRoot, planPath,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs a claimed testcase with the supplied executor and validator runner.
	 *
	 * @param runPath the persistent evaluation record
	 * @param caseId the declared testcase identifier
	 * @param trial the already claimed whole-prompt trial number
	 * @param workspaceRoot the initially absent owned workspace root
	 * @param planPath the typed executor and validator command plan
	 * @param runner the runner that retains executor and validator results
	 * @return the terminal status and retained evidence paths
	 * @throws IOException if the transaction cannot be prepared or its terminal evidence cannot be recorded
	 */
	static ObjectNode executeClaimed(Path runPath, String caseId, int trial, Path workspaceRoot, Path planPath,
		HarnessEvaluationRunner runner) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return executeClaimed(scope.json(), runPath, caseId, trial, workspaceRoot, planPath, runner);
		}
	}

	/**
	 * Runs a claimed testcase with its operation-owned mapper.
	 *
	 * @param json the operation's JSON mapper
	 * @param runPath the persistent evaluation record
	 * @param caseId the declared testcase identifier
	 * @param trial the already claimed whole-prompt trial number
	 * @param workspaceRoot the initially absent owned workspace root
	 * @param planPath the typed executor and validator command plan
	 * @param runner the runner that retains executor and validator results
	 * @return the terminal status and retained evidence paths
	 * @throws IOException if the transaction cannot be prepared or its terminal evidence cannot be recorded
	 */
	private static ObjectNode executeClaimed(JsonMapper json, Path runPath, String caseId, int trial,
		Path workspaceRoot, Path planPath, HarnessEvaluationRunner runner) throws IOException
	{
		return Executor.execute(json, runPath, caseId, trial, workspaceRoot, planPath, runner);
	}

	/**
	 * Stages an immutable executor-evidence copy for a direct-evidence validator.
	 * <p>
	 * Codex accepts evidence roots only as directories. Passing the original output file as a root makes it treat that
	 * file as a directory, preventing the validator from reading evidence or writing its verdict. The validator-owned
	 * copy gives it the actual project and exact output through one directory without modifying the executor's immutable
	 * retained project or mounting the outer fixture directory, which could expose the unchanged starter project.
	 *
	 * @param retainedProject the executor project retained for independent review
	 * @param rawResult the executor's original protocol output
	 * @param directEvidenceProject the initially absent validator-owned evidence copy
	 * @throws IOException if the output cannot be staged for review
	 */
	static void stageDirectValidatorEvidence(Path retainedProject, Path rawResult, Path directEvidenceProject)
		throws IOException
	{
		EvaluationFiles.copyFixture(retainedProject, directEvidenceProject);
		Files.copy(rawResult, directValidatorOutput(directEvidenceProject), StandardCopyOption.REPLACE_EXISTING);
		if (!writeFinalTaskResponse(directValidatorOutput(directEvidenceProject),
			directValidatorResponse(directEvidenceProject)))
		{
			throw new IOException("Executor evidence contains no final task response for semantic review: " + rawResult);
		}
	}

	/**
	 * Returns the fixed executor-output location exposed within a retained project.
	 *
	 * @param retainedProject the validator-owned direct evidence project
	 * @return the direct-evidence output location
	 */
	static Path directValidatorOutput(Path retainedProject)
	{
		return retainedProject.resolve(DIRECT_VALIDATOR_EXECUTOR_OUTPUT);
	}

	/**
	 * Returns the compact final task response exposed beside direct validator provenance.
	 *
	 * @param retainedProject the validator-owned direct evidence project
	 * @return the final response location
	 */
	static Path directValidatorResponse(Path retainedProject)
	{
		return retainedProject.resolve(DIRECT_VALIDATOR_FINAL_RESPONSE);
	}

	/**
	 * Determines whether the selected validator runner supports immutable evidence paths directly.
	 *
	 * @param validatorPlan the selected validator command contract
	 * @param planPath the plan declaring that contract
	 * @return whether the validator accepts CAT read-only evidence options
	 * @throws IOException if the declared capability is malformed
	 */
	static boolean directEvidenceValidator(ObjectNode validatorPlan, Path planPath) throws IOException
	{
		assert validatorPlan != null;
		assert planPath != null;
		JsonNode value = validatorPlan.path("read_only_evidence");
		if (value.isMissingNode())
			return false;
		if (!value.isBoolean())
			throw new IOException("Validator read_only_evidence must be true or false: " + planPath);
		return value.booleanValue();
	}

	/**
	 * Runs every testcase in each newly claimed whole-prompt trial and updates the evaluation decision as each complete
	 * suite finishes.
	 *
	 * @param runPath the persistent evaluation record
	 * @param trialCount the number of whole-prompt trials to run
	 * @param workspaceRoot an initially empty owned batch workspace
	 * @param planPath the typed executor and validator command plan
	 * @param maximumConcurrency the shared capacity available to this batch
	 * @return the resulting evaluation decision
	 * @throws IOException if a trial cannot be claimed or a worker cannot record its terminal result
	 */
	private static ObjectNode runBatch(Path runPath, int trialCount, Path workspaceRoot, Path planPath,
		int maximumConcurrency)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return runBatch(runPath, trialCount, workspaceRoot, planPath, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs a claimed certification batch with the supplied executor and validator runner.
	 *
	 * @param runPath the persistent evaluation record
	 * @param trialCount the number of whole-prompt trials to run
	 * @param workspaceRoot an initially empty owned batch workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared capacity available to this batch
	 * @param runner the runner that retains executor and validator results
	 * @return the resulting evaluation decision
	 * @throws IOException if a trial cannot be claimed or a worker cannot record its terminal result
	 */
	static ObjectNode runBatch(Path runPath, int trialCount, Path workspaceRoot, Path planPath,
		int maximumConcurrency, HarnessEvaluationRunner runner) throws IOException
	{
		runPath = runPath.toAbsolutePath().normalize();
		workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		planPath = planPath.toAbsolutePath().normalize();
		validateMaximumConcurrency(maximumConcurrency);
		bindExecutionPlan(runPath, planPath);
		ensureCertifyingRun(runPath);
		EvaluationWorkspaceLifecycle.prepareTerminalWorkspace(workspaceRoot);
		List<Integer> trials = claimTrialRange(runPath, trialCount, false);
		return runClaimedBatch(runPath, trials, workspaceRoot, planPath, maximumConcurrency, true, runner);
	}

	/**
	 * Runs one selected testcase through the executor and independent validator without claiming certification.
	 *
	 * <p>
	 * This short path exposes routing, fixture, runner, and validator mistakes before a revision starts a wide
	 * whole-suite measurement. Its partial evidence is deliberately not a compliance result and cannot be resumed as one.
	 *
	 * @param runPath the separately created non-certifying evaluation record
	 * @param caseId the selected discriminating testcase
	 * @param workspaceRoot an initially empty owned smoke workspace
	 * @param planPath the typed executor and validator command plan
	 * @param maximumConcurrency the shared worker capacity
	 * @return the retained testcase result and its non-certifying classification
	 * @throws IOException if the selected testcase cannot retain terminal evidence
	 */
	private static ObjectNode smoke(Path runPath, String caseId, Path workspaceRoot, Path planPath,
		int maximumConcurrency) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return smoke(runPath, caseId, workspaceRoot, planPath, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs one selected testcase with the supplied executor and validator runner without claiming certification.
	 *
	 * @param runPath the separately created non-certifying evaluation record
	 * @param caseId the selected discriminating testcase
	 * @param workspaceRoot an initially empty owned smoke workspace
	 * @param planPath the typed executor and validator command plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner that retains executor and validator results
	 * @return the retained testcase result and its non-certifying classification
	 * @throws IOException if the selected testcase cannot retain terminal evidence
	 */
	static ObjectNode smoke(Path runPath, String caseId, Path workspaceRoot, Path planPath, int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		Path normalizedPlanPath = planPath.toAbsolutePath().normalize();
		validateMaximumConcurrency(maximumConcurrency);
		bindExecutionPlan(normalizedRunPath, normalizedPlanPath);
		WORKSPACE_LIFECYCLE.prepare(normalizedRunPath, normalizedWorkspaceRoot);
		return runOwnedNonCertifyingEvaluation(normalizedRunPath, normalizedWorkspaceRoot, "executing_smoke_" + caseId,
			() -> runSmoke(normalizedRunPath, caseId, normalizedWorkspaceRoot, normalizedPlanPath,
				maximumConcurrency, runner));
	}

	/**
	 * Runs one complete durable suite as a non-certifying candidate regression check.
	 *
	 * @param runPath the prepared certification record
	 * @param workspaceRoot the initially absent evaluator workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @return the terminal check result
	 * @throws IOException if checking cannot retain its evidence
	 */
	private static ObjectNode runRegressionCheck(Path runPath, Path workspaceRoot, Path planPath,
		int maximumConcurrency) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return runRegressionCheck(runPath, workspaceRoot, planPath, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs a complete non-certifying regression check with the supplied executor and validator runner.
	 *
	 * @param runPath the prepared certification record
	 * @param workspaceRoot the initially absent evaluator workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner that retains executor and validator results
	 * @return the terminal check result
	 * @throws IOException if checking cannot retain its evidence
	 */
	static ObjectNode runRegressionCheck(Path runPath, Path workspaceRoot, Path planPath, int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		Path normalizedPlanPath = planPath.toAbsolutePath().normalize();
		validateMaximumConcurrency(maximumConcurrency);
		bindExecutionPlan(normalizedRunPath, normalizedPlanPath);
		withRunLock(normalizedRunPath, () ->
		{
			ObjectNode run = object(read(normalizedRunPath), "run");
			if (!text(run, "purpose", normalizedRunPath).equals("certification"))
				throw new IOException("A regression check requires a certification suite: " + normalizedRunPath);
			if (run.has("mode"))
				throw new IOException("Evaluation record already has an execution mode: " + normalizedRunPath);
			run.put("mode", "regression_check");
			write(normalizedRunPath, run);
		});
		WORKSPACE_LIFECYCLE.prepare(normalizedRunPath, normalizedWorkspaceRoot);
		try (ToolScope scope = new ToolScope())
		{
			return runOwnedNonCertifyingEvaluation(normalizedRunPath, normalizedWorkspaceRoot, "executing_regression_check",
				() -> RegressionCheck.execute(scope.json(), normalizedRunPath, normalizedWorkspaceRoot, normalizedPlanPath,
					maximumConcurrency, runner));
		}
	}

	/**
	 * Runs every retained routing-only testcase once without contributing a compliance trial.
	 *
	 * <p>
	 * A router change can fix the originally failed route while breaking another positive, negative, or boundary
	 * route. This operation checks the complete generated matrix before publishing a router-promotion receipt, rather
	 * than treating one repaired route as evidence that the router remains safe for the other ordinary tasks.
	 *
	 * @param runPath the prepared routing-matrix record
	 * @param workspaceRoot the initially absent evaluator workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @return the completed routing-matrix summary
	 * @throws IOException if the run is not a routing matrix or cannot retain terminal evidence
	 */
	private static ObjectNode runRoutingMatrix(Path runPath, Path workspaceRoot, Path planPath,
		int maximumConcurrency) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return runRoutingMatrix(runPath, workspaceRoot, planPath, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs every retained routing-only testcase with the supplied executor runner.
	 *
	 * @param runPath the prepared routing-matrix record
	 * @param workspaceRoot the initially absent evaluator workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner that retains each executor result
	 * @return the completed routing-matrix summary
	 * @throws IOException if the run is not a routing matrix or cannot retain terminal evidence
	 */
	static ObjectNode runRoutingMatrix(Path runPath, Path workspaceRoot, Path planPath, int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		Path normalizedPlanPath = planPath.toAbsolutePath().normalize();
		validateMaximumConcurrency(maximumConcurrency);
		bindExecutionPlan(normalizedRunPath, normalizedPlanPath);
		withRunLock(normalizedRunPath, () ->
		{
			ObjectNode run = object(read(normalizedRunPath), "run");
			Path suitePath = path(text(run, "suite", normalizedRunPath));
			ObjectNode suite = SuiteContract.read(suitePath);
			if (SuitePurpose.parse(text(run, "purpose", normalizedRunPath)) != SuitePurpose.DIAGNOSTIC ||
				SuiteContract.diagnosticKind(suite, suitePath, SuitePurpose.DIAGNOSTIC) != DiagnosticKind.ROUTING_ONLY)
			{
				throw new IOException("A routing matrix requires a generated routing-only diagnostic run: " +
					normalizedRunPath);
			}
			if (run.has("mode"))
				throw new IOException("Evaluation record already has an execution mode: " + normalizedRunPath);
			run.put("mode", "routing_matrix");
			write(normalizedRunPath, run);
		});
		WORKSPACE_LIFECYCLE.prepare(normalizedRunPath, normalizedWorkspaceRoot);
		try (ToolScope scope = new ToolScope())
		{
			return runOwnedNonCertifyingEvaluation(normalizedRunPath, normalizedWorkspaceRoot, "executing_routing_matrix",
				() -> RoutingMatrix.execute(scope.json(), normalizedRunPath, normalizedWorkspaceRoot, normalizedPlanPath,
					maximumConcurrency, runner));
		}
	}

	/**
	 * Writes a complete JSON record before replacing its prior version.
	 *
	 * @param destination the durable record destination
	 * @param value the complete new record
	 * @throws IOException if the record cannot be atomically published
	 */
	public static void writeAtomically(Path destination, ObjectNode value) throws IOException
	{
		Path parent = destination.getParent();
		if (parent == null)
			throw new IOException("Record has no parent directory: " + destination);
		Path temporary = Files.createTempFile(parent, ".prompt-evaluation-", ".json");
		try
		{
			write(temporary, value);
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Executes the selected smoke testcase with the supplied executor and validator runner.
	 *
	 * @param runPath the persistent evaluation record
	 * @param caseId the selected discriminating testcase
	 * @param workspaceRoot the empty smoke workspace
	 * @param planPath the typed executor and validator command plan
	 * @param maximumConcurrency the shared worker capacity
	 * @param runner the runner that retains executor and validator results
	 * @return the retained testcase result
	 * @throws IOException if the selected testcase cannot retain terminal evidence
	 */
	static ObjectNode runSmoke(Path runPath, String caseId, Path workspaceRoot, Path planPath,
		int maximumConcurrency, HarnessEvaluationRunner runner) throws IOException
	{
		Path finalRunPath = runPath;
		withRunLock(finalRunPath, () ->
		{
			ObjectNode record = object(read(finalRunPath), "run");
			if (record.has("mode") && !record.path("mode").asString().equals("smoke"))
				throw new IOException("Evaluation record already has a mode and cannot become a smoke run: " + finalRunPath);
			if (!record.has("mode"))
				record.put("mode", "smoke");
			write(finalRunPath, record);
		});
		ObjectNode run = object(read(runPath), "run");
		Path suitePath = path(text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		ObjectNode selected = null;
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = object(node, "test case");
			if (text(testCase, "id", suitePath).equals(caseId))
			{
				selected = testCase;
				break;
			}
		}
		if (selected == null)
			throw new IOException("Suite does not declare smoke testcase: " + caseId);
		List<Integer> trials = claimTrialRange(runPath, 1, false);
		ObjectNode plan = object(read(planPath), "execution plan");
		List<ExecutorEvidence> evidence = ExecutionScheduler.executeCase(runPath, suitePath, selected,
			trials,
			workspaceRoot, plan, maximumConcurrency, runner);
		if (!evidence.isEmpty())
		{
			try (ToolScope scope = new ToolScope())
			{
				ValidatorLifecycle.recordCrossTrial(ValidatorExecution.access(scope.json(), runPath, workspaceRoot,
					planPath, plan, runner), evidence, workspaceRoot.resolve("validator-batch"));
			}
		}
		ObjectNode result = newObject();
		result.put("classification", "non_certifying_smoke");
		result.put("case_id", caseId);
		result.put("trial", trials.getFirst());
		result.put("workspace", workspaceRoot.toString());
		withRunLock(finalRunPath, () ->
		{
			ObjectNode record = object(read(finalRunPath), "run");
			ObjectNode completed = record.putObject("smoke_result");
			completed.put("classification", "non_certifying_smoke");
			completed.put("case_id", caseId);
			completed.put("trial", trials.getFirst());
			completed.put("completed_at_epoch_millis", System.currentTimeMillis());
			write(finalRunPath, record);
		});
		return result;
	}

	/**
	 * Supplies evaluator operations used by the bounded capability-diagnostic lifecycle.
	 *
	 * @param json the operation's JSON mapper
	 * @return the bounded capability-diagnostic owner
	 */
	static CapabilityDiagnostic capabilityDiagnostic(JsonMapper json)
	{
		return new CapabilityDiagnostic(new CapabilityDiagnostic.Access()
		{
			@Override
			public void validateMaximumConcurrency(int maximumConcurrency) throws IOException
			{
				PromptEvaluation.validateMaximumConcurrency(maximumConcurrency);
			}

			@Override
			public void bindExecutionPlan(Path runPath, Path planPath) throws IOException
			{
				PromptEvaluation.bindExecutionPlan(runPath, planPath);
			}

			@Override
			public void prepareOwnedWorkspace(Path runPath, Path workspaceRoot) throws IOException
			{
				WORKSPACE_LIFECYCLE.prepare(runPath, workspaceRoot);
			}

			@Override
			public ObjectNode runOwned(Path runPath, Path workspaceRoot, String phase,
				CapabilityDiagnostic.Operation operation) throws IOException
			{
				return runOwnedNonCertifyingEvaluation(runPath, workspaceRoot, phase, operation::run);
			}

			@Override
			public ObjectNode readRun(Path runPath) throws IOException
			{
				return object(read(runPath), "run");
			}

			@Override
			public void writeRun(Path runPath, ObjectNode run) throws IOException
			{
				write(runPath, run);
			}

			@Override
			public void withRunLock(Path runPath, CapabilityDiagnostic.Operation operation) throws IOException
			{
				PromptEvaluation.withRunLock(runPath, operation::run);
			}

			@Override
			public String text(ObjectNode value, String field, Path source) throws IOException
			{
				return PromptEvaluation.text(value, field, source);
			}

			@Override
			public Path path(String value)
			{
				return PromptEvaluation.path(value);
			}

			@Override
			public ObjectNode object(JsonNode value, String description) throws IOException
			{
				return PromptEvaluation.object(value, description);
			}

			@Override
			public ArrayNode array(JsonNode value, String description, Path source) throws IOException
			{
				return PromptEvaluation.array(value, description, source);
			}

			@Override
			public ObjectNode object()
			{
				return json.createObjectNode();
			}

			@Override
			public List<Integer> claimTrials(Path runPath, int count) throws IOException
			{
				return claimTrialRange(runPath, count, false);
			}

			@Override
			public void executeAndRecord(Path runPath, Path suitePath, ObjectNode testCase, List<Integer> trials,
				Path workspaceRoot, Path planPath, int maximumConcurrency, HarnessEvaluationRunner runner)
				throws IOException
			{
				ObjectNode plan = object(read(planPath), "execution plan");
				List<ExecutorEvidence> evidence = ExecutionScheduler.executeCase(runPath, suitePath, testCase, trials,
					workspaceRoot, plan, maximumConcurrency, runner);
				if (!evidence.isEmpty())
				{
					ValidatorLifecycle.recordCrossTrial(ValidatorExecution.access(json, runPath, workspaceRoot, planPath, plan,
						runner), evidence, workspaceRoot.resolve("validator-batch"));
				}
			}

			@Override
			public ObjectNode outcomeCounts(ObjectNode run, Path runPath) throws IOException
			{
				return PromptEvaluation.outcomeCounts(run, runPath);
			}

			@Override
			public boolean hasInfrastructureFailure(Path runPath) throws IOException
			{
				return PromptEvaluation.hasInfrastructureFailure(runPath);
			}
		});
	}

	/**
	 * Runs a smoke or diagnostic while publishing its workspace ownership and current state.
	 *
	 * <p>
	 * The workspace returned by {@code prepare} is the same workspace this method locks, updates, and later records
	 * as terminal. Using a parent directory for the status files made the public prepare/run commands disagree about
	 * which directory owned the work.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the prepared evaluator workspace
	 * @param phase the current observable non-certifying phase
	 * @param operation the work that retains a terminal non-certifying result
	 * @return the retained operation result
	 * @throws IOException if the workspace is unowned, another evaluator is active, or the operation fails
	 */
	private static ObjectNode runOwnedNonCertifyingEvaluation(Path runPath, Path workspaceRoot, String phase,
		IoSupplier<ObjectNode> operation) throws IOException
	{
		requireThat(runPath, "runPath").isNotNull();
		requireThat(workspaceRoot, "workspaceRoot").isNotNull();
		requireThat(phase, "phase").isNotBlank();
		requireThat(operation, "operation").isNotNull();
		return WORKSPACE_LIFECYCLE.run(runPath, workspaceRoot, phase, context -> operation.get(), result -> true,
			failure ->
			{
				throw failure;
			});
	}

	/**
	 * Records the workspace that currently owns an evaluator after that evaluator acquired its exclusive lock.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the lock-owning workspace
	 * @throws IOException if the record cannot be updated
	 */
	private static void recordActiveWorkspace(Path runPath, Path workspaceRoot) throws IOException
	{
		withRunLock(runPath, () ->
		{
			ObjectNode run = object(read(runPath), "run");
			run.put("active_workspace", workspaceRoot.toString());
			write(runPath, run);
		});
	}

	/**
	 * Clears the completed evaluator's active workspace without erasing a newer evaluator's ownership pointer.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the workspace that completed
	 * @throws IOException if the record cannot be updated
	 */
	private static void clearActiveWorkspace(Path runPath, Path workspaceRoot) throws IOException
	{
		withRunLock(runPath, () ->
		{
			ObjectNode run = object(read(runPath), "run");
			if (run.hasNonNull("active_workspace") && path(text(run, "active_workspace", runPath)).equals(workspaceRoot))
			{
				run.remove("active_workspace");
				write(runPath, run);
			}
		});
	}

	/**
	 * Runs claimed whole-prompt trials with the supplied executor and validator runner.
	 *
	 * @param runPath the persistent evaluation record
	 * @param trials the already claimed trial numbers
	 * @param workspaceRoot the owned batch workspace
	 * @param planPath the typed execution plan
	 * @param maximumConcurrency the shared capacity available to this batch
	 * @param stopWhenTerminal whether a terminal statistical decision cancels later claimed trials
	 * @param runner the runner that retains executor and validator results
	 * @return the resulting evaluation decision
	 * @throws IOException if a worker cannot retain its terminal result
	 */
	static ObjectNode runClaimedBatch(Path runPath, List<Integer> trials, Path workspaceRoot, Path planPath,
		int maximumConcurrency, boolean stopWhenTerminal, HarnessEvaluationRunner runner) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		Path suitePath = path(text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		ObjectNode plan = object(read(planPath), "execution plan");
		List<ObjectNode> cases = new ArrayList<>();
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
			cases.add(object(node, "test case"));
		// A trial has at most one active testcase, so each claimed trial consumes at most one shared worker. Fill the
		// configured capacity with distinct trials instead of reserving slots for their later testcases.
		for (List<Integer> roundTrials : ExecutionScheduler.rounds(trials, maximumConcurrency))
		{
			Lifecycle.publishActiveStatus(workspaceRoot.getParent(), "executing_suite_round");
			List<ExecutorEvidence> evidence = ExecutionScheduler.executeRound(runPath, suitePath, cases,
				roundTrials, workspaceRoot,
				plan, maximumConcurrency, stopWhenTerminal, runner);
			try (ToolScope scope = new ToolScope())
			{
				ValidatorLifecycle.recordRound(ValidatorExecution.access(scope.json(), runPath, workspaceRoot, planPath,
					plan, runner), evidence, workspaceRoot, maximumConcurrency);
			}
			if (stopWhenTerminal && !decideDuringBatch(runPath).path("overall_decision").asString().equals("RUNNING"))
			{
				ExecutionScheduler.recordCanceledTrials(runPath, suite, trials, workspaceRoot,
					"A completed suite made further certification impossible.");
				return decide(runPath);
			}
		}
		return decide(runPath);
	}

	/**
	 * Returns claimed trials that do not yet have a retained outcome for one testcase.
	 *
	 * @param runPath the persistent evaluation record
	 * @param caseId the testcase identifier
	 * @param trials the claimed trial numbers
	 * @return the trial numbers without a retained testcase outcome
	 * @throws IOException if the record cannot be read
	 */
	static List<Integer> unrecordedTrials(Path runPath, String caseId, List<Integer> trials) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		ObjectNode index = attemptIndex(run, runPath);
		return trials.stream().filter(trial -> !index.path(Integer.toString(trial)).has(caseId)).toList();
	}

/**
	 * Derives the candidate identity with a mapper owned by this discrete record operation.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the record path used in diagnostics
	 * @return the prompt-and-route identity
	 * @throws IOException if the retained route is malformed
	 */
	private static String candidateIdentity(ObjectNode run, Path runPath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Identity.candidateIdentity(scope.json(), run, runPath);
		}
	}


	/**
	 * Promotes a checked candidate with a mapper owned by the promotion operation.
	 *
	 * @param checkRunPath the completed regression-check record
	 * @param candidateStatePath the candidate state to update
	 * @return the promotion receipt
	 * @throws IOException if promotion cannot be retained
	 */
	private static ObjectNode candidateStatePromotion(Path checkRunPath, Path candidateStatePath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return CandidateState.promote(scope.json(), checkRunPath, candidateStatePath);
		}
	}

	/**
	 * Records regression signatures with a mapper owned by the record operation.
	 *
	 * @param checkRunPath the completed regression-check record
	 * @param candidateStatePath the candidate state to update
	 * @return the updated candidate state
	 * @throws IOException if the signatures cannot be retained
	 */
	private static ObjectNode candidateStateCheckSignatures(Path checkRunPath, Path candidateStatePath)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return CandidateState.recordCheckSignatures(scope.json(), checkRunPath, candidateStatePath);
		}
	}

	/**
	 * Removes incomplete-check evidence with a mapper owned by the repair operation.
	 *
	 * @param candidateStatePath the candidate state to repair
	 * @throws IOException if incomplete evidence cannot be removed
	 */
	private static void discardCandidateCheckEvidence(Path candidateStatePath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			CandidateState.discardIncompleteCandidateCheckEvidence(scope.json(), candidateStatePath);
		}
	}

	/**
	 * Records a formal decision with a mapper owned by the terminal-decision operation.
	 *
	 * @param runPath the formal evaluation record
	 * @param candidateStatePath the candidate state to update
	 * @param decision the terminal decision
	 * @return the updated candidate state, or {@code null} for a running decision
	 * @throws IOException if terminal state cannot be retained
	 */
	private static ObjectNode recordCandidateFormalDecision(Path runPath, Path candidateStatePath,
		ObjectNode decision) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return CandidateState.recordFormalDecision(scope.json(), runPath, candidateStatePath, decision);
		}
	}

	/**
	 * Binds an evaluation to the exact execution plan before it claims any trial.
	 *
	 * @param runPath the persistent evaluation record
	 * @param planPath the immutable execution plan
	 * @throws IOException if the plan is absent or differs from a previously bound plan
	 */
	private static void bindExecutionPlan(Path runPath, Path planPath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			bindExecutionPlan(scope.json(), runPath, planPath);
		}
	}

	/**
	 * Binds an evaluation to its execution plan using the operation-owned mapper.
	 *
	 * @param json the mapper that creates and serializes execution identity records
	 * @param runPath the persistent evaluation record
	 * @param planPath the immutable execution plan
	 * @throws IOException if the plan is absent or differs from a previously bound plan
	 */
	private static void bindExecutionPlan(JsonMapper json, Path runPath, Path planPath) throws IOException
	{
		if (!Files.isRegularFile(planPath))
			throw new IOException("Expected execution plan file: " + planPath);
		String fingerprint = sha256(planPath);
		withRunLock(runPath, () ->
		{
			ObjectNode run = object(read(runPath), "run");
			if (run.has("execution_plan_sha256"))
			{
				if (!text(run, "execution_plan_sha256", runPath).equals(fingerprint) ||
					!text(run, "execution_plan", runPath).equals(planPath.toString()))
				{
					throw new IOException("Execution plan differs from the plan bound to this evaluation: " + planPath);
				}
				return;
			}
			ObjectNode identity = Identity.executionIdentity(json, planPath);
			String identityFingerprint = Identity.executionFingerprint(json, identity);
			ObjectNode promptRoute = Identity.assembledRoute(json, run, identity, runPath);
			RunIntegrity.verifyCandidateBundleDelivery(run, promptRoute, runPath);
			ObjectNode epoch = Identity.evaluationEpoch(json, run, identity, runPath);
			run.put("execution_plan", planPath.toString());
			run.put("execution_plan_sha256", fingerprint);
			run.set("execution_identity", identity);
			run.put("execution_identity_sha256", identityFingerprint);
			run.set("assembled_prompt_route", promptRoute);
			run.put("assembled_prompt_route_sha256", sha256(json.writeValueAsString(promptRoute)));
			if (run.has("candidate_bundle_sha256"))
				run.put("candidate_identity_sha256", text(run, "candidate_bundle_sha256", runPath));
			else
				run.put("candidate_identity_sha256", Identity.candidateIdentity(json, promptRoute));
			run.set("evaluation_epoch", epoch);
			run.put("evaluation_epoch_sha256", sha256(json.writeValueAsString(epoch)));
			run.set("comparison_contract", epoch.deepCopy());
			run.put("comparison_contract_sha256", sha256(json.writeValueAsString(epoch)));
			write(runPath, run);
		});
	}

	/**
	 * Verifies the independent reviewer's semantic controls before it evaluates a candidate response.
	 *
	 * <p>
	 * The controls are fixed response fixtures. They prove that each semantic assertion can be rejected without
	 * rejecting the others, so a candidate trial cannot hide an ambiguous reviewer contract behind a whole-case verdict.
	 * They are verifier calibration evidence, not executor trials and therefore never enter the compliance ledger.
	 *
	 * @param runPath the prepared evaluation record
	 * @param workspaceRoot the evaluation's retained workspace
	 * @param planPath the immutable execution plan
	 * @throws IOException if a declared semantic control does not receive its expected independent verdict
	 */
	private static void verifySemanticControls(Path runPath, Path workspaceRoot, Path planPath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			Calibration.verify(scope.json(), runPath, workspaceRoot, planPath,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Verifies semantic controls with the supplied independent-validator runner.
	 *
	 * @param runPath the prepared evaluation record
	 * @param workspaceRoot the evaluation's retained workspace
	 * @param planPath the immutable execution plan
	 * @param runner the runner that retains validator evidence
	 * @throws IOException if a declared semantic control does not receive its expected independent verdict
	 */
	static void verifySemanticControls(Path runPath, Path workspaceRoot, Path planPath, HarnessEvaluationRunner runner)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			Calibration.verify(scope.json(), runPath, workspaceRoot, planPath, runner);
		}
	}

	/**
	 * Returns whether a testcase still needs a model review after its deterministic checks finish.
	 *
	 * @param evidence the testcase evidence
	 * @return whether the testcase declares a semantic assertion
	 */
	static boolean hasSemanticAssertions(ExecutorEvidence evidence)
	{
		try
		{
			return !SuiteContract.assertionIds(evidence.testCase(), evidence.prompt(), EvaluationKind.SEMANTIC).isEmpty();
		}
		catch (IOException e)
		{
			throw new IllegalArgumentException("Validated testcase has an invalid evaluation kind", e);
		}
	}

	/**
	 * Records a testcase decided entirely by a completed deterministic check, without opening a model-review turn.
	 *
	 * @param runPath the persistent evaluation record
	 * @param evidence the completed executor evidence
	 * @throws IOException if the check receipt is unavailable or cannot be recorded
	 */
	static void recordDeterministicCheckVerdict(Path runPath, ExecutorEvidence evidence) throws IOException
	{
		if (evidence.checkResult() == null)
			throw new IOException("Deterministic testcase has no retained check result: " + verdictKey(evidence));
		AttemptStatus status = AttemptStatus.PASSED;
		if (DeterministicChecks.failed(evidence.checkResult(), evidence.testCase(), evidence.prompt()))
			status = AttemptStatus.FAILED;
		AttemptStatus recordedStatus = status;
		withRunLock(runPath, () -> record(runPath, evidence.caseId(), evidence.trial(), recordedStatus,
			evidence.rawResult(), evidence.checkResult(), evidence.checkResult()));
	}

	/**
	 * Records a validator failure only for a testcase that did not already retain a verdict before that failure.
	 *
	 * @param runPath the persistent evaluation record
	 * @param evidence the retained executor evidence
	 * @param status the terminal status caused by the validator failure
	 * @param validatorResult the retained validator diagnostic
	 * @throws IOException if the record cannot be inspected or updated
	 */
	static void recordIfUnrecorded(Path runPath, ExecutorEvidence evidence, AttemptStatus status, Path validatorResult)
		throws IOException
	{
		recordIfUnrecorded(runPath, evidence, status, validatorResult, evidence.timing());
	}

	/**
	 * Records one terminal outcome only when another evaluator action has not already completed that testcase.
	 *
	 * @param runPath the persistent evaluation record
	 * @param evidence the retained executor evidence
	 * @param status the terminal status caused by the validator result
	 * @param validatorResult the retained validator diagnostic
	 * @param timing the final measured worker timing
	 * @throws IOException if the record cannot be inspected or updated
	 */
	private static void recordIfUnrecorded(Path runPath, ExecutorEvidence evidence, AttemptStatus status,
		Path validatorResult, WorkerTiming timing) throws IOException
	{
		withRunLock(runPath, () ->
		{
			if (unrecordedTrials(runPath, evidence.caseId(), List.of(evidence.trial())).isEmpty())
				return;
			record(runPath, evidence.caseId(), evidence.trial(), status, evidence.rawResult(), validatorResult,
				evidence.checkResult(), timing);
		});
	}

	/**
	 * Records one verdict whose durable map key identifies both its trial and testcase.
	 *
	 * @param runPath the persistent evaluation record
	 * @param evidence the executor evidence
	 * @param verdicts the validator's verdict map
	 * @param validatorResult the retained map file
	 * @param timing the measured worker timing, when available
	 * @throws IOException if a verdict is missing or invalid
	 */
	static void recordCrossTrialVerdict(Path runPath, ExecutorEvidence evidence, ObjectNode verdicts,
		Path validatorResult, WorkerTiming timing) throws IOException
	{
		String key = verdictKey(evidence);
		ObjectNode verdict = object(verdicts.path(key), "batch validator verdict for " + key);
		String verdictStatus = text(verdict, "status", validatorResult);
		if (!verdictStatus.equals("passed") && !verdictStatus.equals("failed"))
			throw new IOException("Batch validator must return passed or failed for " + key);
		ObjectNode assertionVerdicts = object(verdict.path("assertions"), "assertions for " + key);
		Set<String> expectedAssertions = SuiteContract.assertionIds(evidence.testCase(), evidence.prompt(),
			EvaluationKind.SEMANTIC);
		boolean allPassed = deterministicAssertionsPassed(evidence);
		for (JsonNode assertion : array(evidence.testCase().path("assertions"), "assertions", evidence.prompt()))
		{
			String assertionId = text(object(assertion, "assertion"), "id", evidence.prompt());
			if (!expectedAssertions.contains(assertionId))
				continue;
			ObjectNode assertionVerdict = object(assertionVerdicts.path(assertionId),
				"assertion verdict " + assertionId + " for " + key);
			String assertionStatus = text(assertionVerdict, "status", validatorResult);
			if (!assertionStatus.equals("passed") && !assertionStatus.equals("failed"))
				throw new IOException("Batch validator must return passed or failed for assertion " + assertionId + " of " +
					key);
			text(assertionVerdict, "evidence", validatorResult);
			allPassed &= assertionStatus.equals("passed");
		}
		Set<String> actualAssertions = new HashSet<>();
		assertionVerdicts.propertyNames().forEach(actualAssertions::add);
		if (!actualAssertions.equals(expectedAssertions))
			throw new IOException("Batch validator assertions for " + key + " do not match the testcase assertions");
		boolean allSemanticAssertionsPassed = true;
		for (String assertionId : expectedAssertions)
		{
			String assertionStatus = text(object(assertionVerdicts.path(assertionId),
				"assertion verdict " + assertionId + " for " + key), "status", validatorResult);
			allSemanticAssertionsPassed &= assertionStatus.equals("passed");
		}
		AttemptStatus status = AttemptStatus.FAILED;
		if (allPassed && allSemanticAssertionsPassed)
			status = AttemptStatus.PASSED;
		recordIfUnrecorded(runPath, evidence, status, validatorResult, timing);
	}

	/**
	 * Returns whether every assertion delegated to the deterministic checker passed.
	 *
	 * @param evidence the completed testcase evidence
	 * @return whether no deterministic assertion failed
	 * @throws IOException if a declared check is absent or invalid
	 */
	static boolean deterministicAssertionsPassed(ExecutorEvidence evidence) throws IOException
	{
		if (SuiteContract.assertionIds(evidence.testCase(), evidence.prompt(), EvaluationKind.DETERMINISTIC).isEmpty())
			return true;
		if (evidence.checkResult() == null)
			throw new IOException("Deterministic testcase has no retained check result: " + verdictKey(evidence));
		return !DeterministicChecks.failed(evidence.checkResult(), evidence.testCase(), evidence.prompt());
	}

	/**
	 * Returns a stable verdict-map key for one isolated testcase trial.
	 *
	 * @param evidence the testcase evidence
	 * @return the verdict-map key
	 */
	static String verdictKey(ExecutorEvidence evidence)
	{
		return "trial-" + evidence.trial() + "/" + evidence.caseId();
	}

	/**
	 * Runs complete-suite batches until the evaluation reaches its terminal decision.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot an initially empty owned evaluation workspace
	 * @param planPath the typed executor and validator command plan
	 * @param maximumConcurrency the shared capacity available to each batch
	 * @return the terminal evaluation decision
	 * @throws IOException if a batch cannot retain its terminal evidence
	 */
	static ObjectNode runUntilTerminal(Path runPath, Path workspaceRoot, Path planPath, int maximumConcurrency)
		throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return runUntilTerminal(runPath, workspaceRoot, planPath, maximumConcurrency,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs complete-suite batches with the supplied executor and validator runner until a terminal decision is retained.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot an initially empty owned evaluation workspace
	 * @param planPath the typed executor and validator command plan
	 * @param maximumConcurrency the shared capacity available to each batch
	 * @param runner the runner that retains executor and validator results
	 * @return the terminal evaluation decision
	 * @throws IOException if a batch cannot retain its terminal evidence
	 */
	public static ObjectNode runUntilTerminal(Path runPath, Path workspaceRoot, Path planPath, int maximumConcurrency,
		HarnessEvaluationRunner runner) throws IOException
	{
		Path normalizedRunPath = runPath.toAbsolutePath().normalize();
		Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
		Path normalizedPlanPath = planPath.toAbsolutePath().normalize();
		validateMaximumConcurrency(maximumConcurrency);
		ensureCertifyingRun(normalizedRunPath);
		bindMaximumConcurrency(normalizedRunPath, maximumConcurrency);
		WORKSPACE_LIFECYCLE.prepare(normalizedRunPath, normalizedWorkspaceRoot);

		int[] batch = {1};
		return WORKSPACE_LIFECYCLE.run(normalizedRunPath, normalizedWorkspaceRoot, "starting_certification", context ->
		{
			validateRunInputs(object(read(normalizedRunPath), "run"), normalizedRunPath);
			return Certification.run(new Certification.Access()
			{
				@Override
				public void publishSemanticControls() throws IOException
				{
					context.publishPhase("verifying_semantic_controls");
				}

				@Override
				public void verifySemanticControls() throws IOException
				{
					PromptEvaluation.verifySemanticControls(normalizedRunPath, normalizedWorkspaceRoot, normalizedPlanPath,
						runner);
				}

				@Override
				public void verifyHeartbeat() throws IOException
				{
					context.verifyHeartbeat();
				}

				@Override
				public void publishBatchSelection() throws IOException
				{
					context.publishPhase("selecting_next_batch");
				}

				@Override
				public ObjectNode decide() throws IOException
				{
					return PromptEvaluation.decide(normalizedRunPath);
				}

				@Override
				public Path resumableBatch() throws IOException
				{
					return findIncompleteBatch(normalizedRunPath, normalizedWorkspaceRoot);
				}

				@Override
				public ObjectNode resume(Path resumedBatch) throws IOException
				{
					return runClaimedBatch(normalizedRunPath, trialsIn(resumedBatch), resumedBatch,
						normalizedPlanPath, maximumConcurrency, true, runner);
				}

				@Override
				public int remainingTrials() throws IOException
				{
					ObjectNode run = object(read(normalizedRunPath), "run");
					return MAXIMUM_SUITE_RUNS - run.path("next_trial").asInt() + 1;
				}

				@Override
				public int maximumConcurrency()
				{
					return maximumConcurrency;
				}

				@Override
				public ObjectNode runFresh(int trialCount) throws IOException
				{
					while (Files.exists(normalizedWorkspaceRoot.resolve("batch-" + batch[0])))
						++batch[0];
					ObjectNode result = runBatch(normalizedRunPath, trialCount,
						normalizedWorkspaceRoot.resolve("batch-" + batch[0]), normalizedPlanPath,
						maximumConcurrency, runner);
					++batch[0];
					return result;
				}
			});
		}, decision -> !decision.path("overall_decision").asString().equals("RUNNING"),
			failure -> decide(normalizedRunPath));
	}

	/**
	 * Rejects a partial diagnostic run before it can be mistaken for certification evidence.
	 *
	 * @param runPath the selected evaluation record
	 * @throws IOException if the record is a smoke-only run
	 */
	private static void ensureCertifyingRun(Path runPath) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		if (!text(run, "purpose", runPath).equals("certification"))
			throw new IOException("Only a certification record can run or report compliance: " + runPath);
		if (run.path("mode").asString().equals("smoke"))
			throw new IOException("A non-certifying smoke record cannot run certification: " + runPath);
		if (run.path("mode").asString().equals("regression_check") ||
			run.path("mode").asString().equals("routing_matrix"))
			throw new IOException("A non-certifying regression check cannot run certification: " + runPath);
	}

	/**
	 * Requires a record prepared by the paired benchmark operation.
	 *
	 * @param runPath the evaluation record
	 * @throws IOException if the record is not a fixed-budget benchmark
	 */
	private static void ensureFixedBenchmarkRun(Path runPath) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		if (!run.path("mode").asString().equals("benchmark") || !run.has("fixed_trial_budget"))
			throw new IOException("A fixed benchmark trial requires a paired benchmark record: " + runPath);
		Benchmark.validateTrialCount(run.path("fixed_trial_budget").asInt(-1));
	}

	/**
	 * Locates the oldest batch whose claimed trials still lack one or more testcase outcomes.
	 *
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the evaluator-owned workspace
	 * @return the incomplete batch, or {@code null} when every retained batch is complete
	 * @throws IOException if retained batch or evaluation data cannot be read
	 */
	private static Path findIncompleteBatch(Path runPath, Path workspaceRoot) throws IOException
	{
		try (Stream<Path> entries = Files.list(workspaceRoot))
		{
			for (Path batch : entries.filter(Files::isDirectory).sorted().toList())
			{
				if (!batch.getFileName().toString().matches("batch-[0-9]+"))
					continue;
				List<Integer> trials = trialsIn(batch);
				if (trials.isEmpty())
					continue;
				ObjectNode run = object(read(runPath), "run");
				ObjectNode suite = SuiteContract.read(path(text(run, "suite", runPath)));
				for (JsonNode node : array(suite.path("cases"), "cases", runPath))
					if (!unrecordedTrials(runPath, text(object(node, "test case"), "id", runPath), trials).isEmpty())
						return batch;
			}
		}
		return null;
	}

	/**
	 * Returns an incomplete batch that a replacement evaluator can repeat from the retained execution plan.
	 *
	 * @param run the persistent evaluation record
	 * @param runPath the evaluation-record path
	 * @param workspaceRoot the evaluator-owned workspace
	 * @return the incomplete batch, or {@code null} when no retained plan can resume one safely
	 * @throws IOException if a declared plan or batch cannot be read
	 */
	public static Path resumableBatch(ObjectNode run, Path runPath, Path workspaceRoot) throws IOException
	{
		if (!run.hasNonNull("execution_plan"))
			return null;
		Path plan = path(text(run, "execution_plan", runPath));
		if (!Files.isRegularFile(plan))
			return null;
		return findIncompleteBatch(runPath, workspaceRoot);
	}

	/**
	 * Reads the trial numbers already claimed in one retained batch workspace.
	 *
	 * @param batch the retained batch workspace
	 * @return the claimed trial numbers in ascending order
	 * @throws IOException if the batch cannot be read
	 */
	private static List<Integer> trialsIn(Path batch) throws IOException
	{
		try (Stream<Path> entries = Files.list(batch))
		{
			return entries.filter(Files::isDirectory).map(path -> path.getFileName().toString()).filter(name ->
				name.matches("trial-[0-9]+")).map(name -> Integer.parseInt(name.substring(6))).sorted().toList();
		}
	}

	/**
	 * Moves terminal evaluator evidence and rewrites every retained artifact path that the move changes.
	 *
	 * @param json the operation-owned mapper for retained JSON receipts
	 * @param runPath the persistent evaluation record
	 * @param workspaceRoot the owned terminal-evidence directory
	 * @param durableDirectory the initially absent retained destination beneath the CAT work root
	 * @param temporaryRoot the resolved CAT work temp directory
	 * @param workRoot the resolved CAT work root
	 * @return the retained-evidence handoff receipt
	 * @throws IOException if the record is not terminal, the workspace is not owned by the evaluator, or evidence cannot
	 *         be moved and rewritten
	 */
	static ObjectNode handoff(JsonMapper json, Path runPath, Path workspaceRoot, Path durableDirectory,
		Path temporaryRoot, Path workRoot)
		throws IOException
	{
		return EvidenceHandoff.handoff(json, runPath, workspaceRoot, durableDirectory, temporaryRoot, workRoot);
	}

	/**
	 * Validates a shared harness-concurrency limit.
	 *
	 * @param maximumConcurrency the requested shared capacity
	 * @throws IOException if the value exceeds the shared limit
	 */
	static void validateMaximumConcurrency(int maximumConcurrency) throws IOException
	{
		if (maximumConcurrency < 1 || maximumConcurrency > HarnessConcurrencyLimit.DEFAULT_MAXIMUM_CONCURRENCY)
			throw new IOException("Batch concurrency must be between 1 and " +
				HarnessConcurrencyLimit.DEFAULT_MAXIMUM_CONCURRENCY);
	}

	/**
	 * Stops runner processes that would otherwise outlive an interrupted evaluator and keep its evidence mutable.
	 */
	private static void interruptActiveEvaluations()
	{
		WORKSPACE_LIFECYCLE.interruptActive();
	}

	/**
	 * Creates the complete JSONL protocol for one testcase, including its optional steering, interruption, and resumed
	 * turns.
	 *
	 * @param testCase the validated testcase contract
	 * @param suitePath the testcase-suite path
	 * @param prompt the initial prompt path
	 * @return the declared messages followed by explicit bridge completion
	 * @throws IOException if the JSONL request cannot be serialized
	 */
	static String bridgeInput(ObjectNode testCase, Path suitePath, Path prompt) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return PromptInput.forTestCase(scope.json(), testCase, suitePath, prompt);
		}
	}

	/**
	 * Returns the Runner-visible part of one scenario document.
	 *
	 * @param contents the complete scenario document
	 * @param scenarioPath the document used for diagnostics
	 * @return the scenario text without evaluator assertions
	 * @throws IOException if the document has no valid scenario boundary
	 */
	static String scenarioTask(String contents, Path scenarioPath) throws IOException
	{
		return PromptInput.scenarioTask(contents, scenarioPath);
	}

	/**
	 * Creates the standard one-turn bridge input for a validator that does not declare mid-turn controls.
	 *
	 * @param prompt the complete validator prompt
	 * @return one turn followed by explicit bridge completion
	 * @throws IOException if JSON serialization fails
	 */
	public static String bridgeInput(String prompt) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return PromptInput.forPrompt(scope.json(), prompt);
		}
	}

	/**
	 * Runs each declared deterministic-check good and bad fixture before a harness build or model turn starts.
	 *
	 * @param json the operation's JSON mapper
	 * @param suitePath the immutable testcase-suite manifest
	 * @param workspace the empty workspace that owns transient check results
	 * @throws IOException if the suite is invalid or a check disagrees with one of its declared control fixtures
	 */
	static void preflight(JsonMapper json, Path suitePath, Path workspace) throws IOException
	{
		Preflight.preflight(json, suitePath, workspace);
	}

	/**
	 * Recreates one direct-routing diagnostic from an immutable ordinary-use certification case.
	 *
	 * <p>
	 * The normal case first establishes whether ordinary routing and application agree. This command exists only when
	 * that retained evidence cannot identify which one failed. It preserves the task and fixture, replaces only its
	 * delivery setup, and creates a separate non-certifying manifest.
	 *
	 * @param json the operation's JSON mapper
	 * @param sourceRunPath the retained failed organic evaluation
	 * @param caseId the ambiguous retained case
	 * @param trial the failed whole-prompt trial
	 * @param outputPath the initially absent diagnostic scenario-suite root
	 * @throws IOException if the source suite cannot supply an expected selected rule path
	 */
	private static void createDirectDiagnostic(JsonMapper json, Path sourceRunPath, String caseId, int trial,
		Path outputPath)
		throws IOException
	{
		Diagnostics.createDirect(diagnostics(json), sourceRunPath, caseId, trial, outputPath);
	}

	/**
	 * Recreates a bounded routing diagnostic from retained ordinary-use evidence.
	 *
	 * <p>
	 * The diagnostic keeps the user's task and fixture intact but asks the executor to stop after selecting and
	 * loading its applicable routes. It therefore isolates missed selection without treating its controlled instruction
	 * as organic certification evidence.
	 *
	 * @param json the operation's JSON mapper
	 * @param sourceRunPath the retained failed organic evaluation
	 * @param caseId the retained routing failure that justified this matrix
	 * @param trial the failed whole-prompt trial
	 * @param outputPath the initially absent diagnostic scenario-suite root
	 * @throws IOException if the source case cannot establish an organic route expectation
	 */
	private static void createRoutingDiagnostic(JsonMapper json, Path sourceRunPath, String caseId, int trial,
		Path outputPath)
		throws IOException
	{
		Diagnostics.createRouting(diagnostics(json), sourceRunPath, caseId, trial, outputPath);
	}

	/**
	 * Requires the retained evidence that makes an executor-profile comparison useful.
	 *
	 * <p>
	 * A stronger executor cannot repair a missing route, a failed check, or an unavailable dependency. This gate
	 * therefore accepts only a failure that the retained record has already localized to the executor's application of
	 * a delivered rule. It also requires three fresh checks to repeat the same failed case, assertion, and observable
	 * boundary across three prompt revisions. Without that recurrence, one random response could make a profile
	 * comparison look meaningful when the next target-profile response would have succeeded.
	 *
	 * @param sourceRunPath the latest retained target-profile regression check
	 * @param certificationSuite the durable certification suite that the check exercised
	 * @param caseId the repeated failed testcase
	 * @param trial the failed whole-prompt trial in the latest check
	 * @param diagnosticKind the isolated question that the generated diagnostic will ask
	 * @throws IOException if the retained evidence does not establish a repeated application or routing decision
	 */
	private static void requireCapabilityDiagnosticSource(Path sourceRunPath, Path certificationSuite, String caseId,
		int trial, DiagnosticKind diagnosticKind) throws IOException
	{
		sourceRunPath = sourceRunPath.toAbsolutePath().normalize();
		ObjectNode sourceRun = object(read(sourceRunPath), "profile-capability source run");
		if (!text(sourceRun, "purpose", sourceRunPath).equals("certification"))
			throw new IOException("A profile-capability diagnostic requires a retained target-profile certification " +
				"check: " + sourceRunPath);
		if (!text(sourceRun, "suite_sha256", sourceRunPath).equals(suiteFingerprint(certificationSuite)))
			throw new IOException("Profile-capability source run exercised a different certification suite: " +
				sourceRunPath);

		FailureClass expectedClass = switch (diagnosticKind)
		{
			case DIRECT_APPLICATION -> FailureClass.APPLICATION;
			case ROUTING_ONLY -> FailureClass.ROUTING_DELIVERY;
		};
		ObjectNode classification = classifyFailure(sourceRunPath, caseId, trial);
		FailureClass actualClass = FailureClass.parse(text(classification, "failure_class", sourceRunPath));
		if (actualClass != expectedClass)
			throw new IOException("Profile-capability diagnostic requires retained " + expectedClass.externalName() +
				" evidence for " + caseId + ", but this attempt establishes " + actualClass.externalName() + ": " +
				text(classification, "evidence", sourceRunPath));
		if (!sourceRun.has("failure_signature_state"))
			throw new IOException("Profile-capability diagnostic requires three fresh target-profile regression checks. " +
				"This check has no failure-signature state: " + sourceRunPath);
		Path statePath = path(text(sourceRun, "failure_signature_state", sourceRunPath));
		ObjectNode state = object(read(statePath), "failure-signature state");
		String candidateHash = Identity.candidateRevision(sourceRun, sourceRunPath);
		for (JsonNode node : array(state.path("failure_signatures"), "failure signatures", statePath))
		{
			ObjectNode signature = object(node, "failure signature");
			ObjectNode observed = object(signature.path("latest_case"), "latest failure signature");
			if (!text(observed, "case_id", statePath).equals(caseId) ||
				!text(signature, "last_observed_check", statePath).equals(sourceRunPath.toString()) ||
				!text(signature, "last_observed_candidate_sha256", statePath).equals(candidateHash))
				continue;
			if (signature.path("observations").asInt() >= CapabilityDiagnostic.trialCount())
			{
				ArrayNode candidateRevisions = array(signature.path("observed_candidate_revisions"),
					"observed candidate revisions", statePath);
				if (candidateRevisions.size() >= CapabilityDiagnostic.trialCount())
					return;
				throw new IOException("Profile-capability diagnostic requires two prompt revisions, each followed by " +
					"fresh target-profile evidence for this decision. This candidate state retains only " +
					candidateRevisions.size() + " candidate revision(s): " + statePath);
			}
		}
		throw new IOException("Profile-capability diagnostic requires the same failed case, assertion, and observable " +
			"boundary in three fresh target-profile checks. Run more checks after distinct prompt revisions or repair " +
			"the localized decision first: " + statePath);
	}

	/**
	 * Describes the bounded observable outcome of one routing-only diagnostic.
	 *
	 * @param expectsSelected whether the diagnostic expects one or more routes to be selected
	 * @param permitsOptionalSelection whether the diagnostic allows one or more additional routes to be selected
	 * @param expectsNotSelected whether the diagnostic expects one or more routes to remain absent
	 * @return the validator instruction for the declared route expectation
	 */
	static String routingDiagnosticInstruction(boolean expectsSelected, boolean permitsOptionalSelection,
		boolean expectsNotSelected)
	{
		String routeOutcome;
		if (expectsSelected && permitsOptionalSelection && expectsNotSelected)
			routeOutcome = "select every required route, may select routes marked optional_selected, and avoid every route " +
				"marked not_selected";
		else if (expectsSelected && permitsOptionalSelection)
			routeOutcome = "select every required route and may select routes marked optional_selected";
		else if (expectsSelected && expectsNotSelected)
			routeOutcome = "select every expected route and avoid every route marked not_selected";
		else if (expectsSelected)
			routeOutcome = "select every expected route";
		else
			routeOutcome = "avoid every route marked not_selected";
		return "Verify only that retained native events " + routeOutcome + " before any project inspection, candidate " +
			"search, implementation, or task answer, and that no such task action occurred.";
	}

	/**
	 * Copies one persistent test fixture into a fresh trial workspace.
	 *
	 * @param json the operation's JSON mapper
	 * @param suitePath the testcase-suite manifest
	 * @param caseId the selected test identifier
	 * @param workspace the initially absent trial workspace
	 * @param output the destination for the prepared-workspace description
	 * @throws IOException if the suite, selected fixture, or destination is invalid
	 */
	private static void prepareTrial(JsonMapper json, Path suitePath, String caseId, Path workspace,
		Consumer<String> output)
		throws IOException
	{
		output.accept(json.writeValueAsString(TrialPreparation.prepare(json, suitePath, caseId, workspace)));
	}

	/**
	 * Copies the executor's retained project and raw result into an isolated validator workspace.
	 *
	 * @param json the operation's JSON mapper
	 * @param retainedProject the project directory owned by the completed harness run
	 * @param rawResult the retained executor event stream or result
	 * @param workspace the initially absent validator workspace
	 * @param output the destination for the prepared-workspace description
	 * @throws IOException if an input is invalid or the evidence cannot be copied
	 */
	private static void prepareValidator(JsonMapper json, Path retainedProject, Path rawResult, Path workspace,
		Consumer<String> output)
		throws IOException
	{
		output.accept(json.writeValueAsString(ValidatorEvidence.describe(json, retainedProject, rawResult, workspace,
			Set.of(".git", "node_modules"), VALIDATOR_FINAL_RESPONSE)));
	}

	/**
	 * Copies executor evidence into a fresh validator workspace while excluding declared generated directories.
	 *
	 * @param retainedProject the project owned by the completed executor
	 * @param rawResult the retained executor result
	 * @param workspace the initially absent validator workspace
	 * @param excludedDirectories generated directory names excluded from the independent review
	 * @return the prepared workspace description
	 * @throws IOException if an input is invalid or the evidence cannot be copied
	 */
	static ObjectNode prepareValidatorContents(Path retainedProject, Path rawResult, Path workspace,
		Set<String> excludedDirectories) throws IOException
	{
		ValidatorEvidence.PreparedWorkspace prepared;
		try (ToolScope scope = new ToolScope())
		{
			prepared = ValidatorEvidence.prepare(scope.json(), retainedProject, rawResult, workspace,
				excludedDirectories, VALIDATOR_FINAL_RESPONSE);
		}
		ObjectNode result = newObject();
		result.put("project", prepared.project().toString());
		result.put("raw_result", prepared.rawResult().toString());
		if (prepared.finalResponse() != null)
			result.put("final_response", prepared.finalResponse().toString());
		return result;
	}

	/**
	 * Extracts the response to the later task from retained native events before an independent reviewer reads them.
	 *
	 * <p>
	 * A route-preparation turn and a task turn can share one retained event stream. Passing that entire stream to a
	 * reviewer makes a short reader-facing answer compete with large hook and protocol records, so the reviewer can miss
	 * a line that the executor actually wrote. This method retains the final completed agent message separately while the
	 * original stream remains the provenance for routing and other event facts.
	 *
	 * @param rawResult the retained executor event stream
	 * @param destination the initially absent final-response artifact
	 * @return whether the stream contains a completed task response
	 * @throws IOException if the input cannot be read or a non-native result is blank
	 */
	private static boolean writeFinalTaskResponse(Path rawResult, Path destination) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return ValidatorEvidence.extractFinalResponse(scope.json(), rawResult, destination);
		}
	}

	/**
	 * Reads the generated directories excluded from a validator's source-evidence copy.
	 *
	 * @param plan the execution plan
	 * @param planPath the execution-plan path
	 * @return supported generated directory names
	 * @throws IOException if the plan names an unsupported exclusion
	 */
	static Set<String> validatorExcludedDirectories(ObjectNode plan, Path planPath) throws IOException
	{
		ArrayNode entries = array(plan.path("validator_excluded_directories"), "validator_excluded_directories", planPath);
		Set<String> result = new HashSet<>();
		for (JsonNode entry : entries)
		{
			if (!entry.isString() || !SUPPORTED_VALIDATOR_EXCLUDED_DIRECTORIES.contains(entry.asString()))
				throw new IOException("Execution plan has an unsupported validator exclusion: " + entry);
			result.add(entry.asString());
		}
		return result;
	}

	/**
	 * Initializes one immutable evaluation record.
	 *
	 * @param suitePath the testcase suite
	 * @param promptArtifact the prompt artifact under evaluation
	 * @param harness the executing harness
	 * @param profile the fixed execution profile
	 * @param executionContext a versioned file that identifies the available context and validator contract
	 * @param runPath the persistent result record
	 * @throws IOException if the inputs are invalid
	 */
	static void begin(Path suitePath, Path promptArtifact, String harness, String profile, Path executionContext,
		Path runPath) throws IOException
	{
		Preparation.begin(suitePath, promptArtifact, harness, profile, executionContext, runPath);
	}

	/**
	 * Creates a run record whose repeated input files are retained under one shared immutable-artifact root.
	 *
	 * @param suitePath the testcase suite
	 * @param promptArtifact the prompt artifact under evaluation
	 * @param harness the executing harness
	 * @param profile the fixed execution profile
	 * @param executionContext the versioned validator context
	 * @param runPath the persistent result record
	 * @param immutableArtifactRoot the shared work root for reusable input copies
	 * @throws IOException if an input is invalid or the record cannot be created
	 */
	static void begin(Path suitePath, Path promptArtifact, String harness, String profile, Path executionContext,
		Path runPath, Path immutableArtifactRoot) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			Preparation.initialize(scope.json(), suitePath, promptArtifact, harness, profile, executionContext, runPath,
				immutableArtifactRoot);
		}
	}

	/**
	 * Reserves distinct fresh whole-prompt trial numbers for concurrent workers.
	 *
	 * @param runPath the persistent result record
	 * @param count the number of trials to reserve
	 * @param output the destination for the reserved trial numbers
	 * @throws IOException if the record is invalid, terminal, or cannot reserve the requested range
	 */
	private static void claimTrials(Path runPath, int count, Consumer<String> output) throws IOException
	{
		List<Integer> trials = claimTrialRange(runPath, count);
		ArrayNode serializedTrials = newArray();
		trials.forEach(serializedTrials::add);
		ObjectNode result = newObject();
		result.set("trials", serializedTrials);
		output.accept(jsonText(result));
	}

	/**
	 * Reserves a contiguous range of whole-prompt trial numbers.
	 *
	 * @param runPath the persistent result record
	 * @param count the number of trials to reserve
	 * @return the claimed trial numbers
	 * @throws IOException if the record is invalid, terminal, or cannot reserve the requested range
	 */
	static List<Integer> claimTrialRange(Path runPath, int count) throws IOException
	{
		return claimTrialRange(runPath, count, true);
	}

	/**
	 * Reserves a contiguous range after an evaluator-owned batch already verified immutable input snapshots.
	 *
	 * @param runPath the persistent result record
	 * @param count the number of trials to reserve
	 * @param verifyInputs whether to reread immutable input artifacts before claiming
	 * @return the claimed trial numbers
	 * @throws IOException if the record is invalid, terminal, or cannot reserve the requested range
	 */
	static List<Integer> claimTrialRange(Path runPath, int count, boolean verifyInputs) throws IOException
	{
		return TrialClaims.reserve(runPath, count, verifyInputs);
	}

	/**
	 * Reserves the next matched range of trials in a paired benchmark.
	 *
	 * @param runPath the paired-benchmark record
	 * @param count the number of trials to reserve
	 * @return the claimed trial numbers
	 * @throws IOException if the record is invalid or exhausted
	 */
	private static List<Integer> claimFixedBenchmarkTrials(Path runPath, int count) throws IOException
	{
		return claimTrialRange(runPath, count, false);
	}

	/**
	 * Adds one independently executed testcase result.
	 *
	 * @param runPath the persistent result record
	 * @param caseId the testcase identifier
	 * @param trial the positive trial number
	 * @param status the validated outcome
	 * @param rawResult the retained raw-result reference
	 * @param validatorResult the retained validator-result reference
	 * @throws IOException if the record is invalid or duplicates a trial
	 */
	private static void record(Path runPath, String caseId, int trial, String status, String rawResult,
		String validatorResult) throws IOException
	{
		runPath = runPath.toAbsolutePath().normalize();
		if (trial < 1)
			throw new IOException("Invalid trial record");
		AttemptStatus parsedStatus = AttemptStatus.parse(status);
		Path rawResultPath = requiredArtifact(rawResult, "raw result");
		Path validatorResultPath = requiredArtifact(validatorResult, "validator result");
		Path finalRunPath = runPath;
		withRunLock(finalRunPath, () -> record(finalRunPath, caseId, trial, parsedStatus, rawResultPath,
			validatorResultPath));
	}

	/**
	 * Adds one validated result while owning the evaluation record.
	 *
	 * @param runPath the persistent result record
	 * @param caseId the testcase identifier
	 * @param trial the claimed whole-prompt trial number
	 * @param status the validated outcome
	 * @param rawResultPath the retained raw-result path
	 * @param validatorResultPath the retained validator-result path
	 * @throws IOException if the record is invalid or duplicates a trial
	 */
	static void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResultPath,
		Path validatorResultPath) throws IOException
	{
		record(runPath, caseId, trial, status, rawResultPath, validatorResultPath, null);
	}

	/**
	 * Adds one validated result and, when one ran, its deterministic-check receipt while owning the evaluation record.
	 *
	 * <p>
	 * The receipt is retained with the later validator verdict because the two establish different facts: the checker
	 * proves mechanical prerequisites such as delivery, while the validator decides the remaining reader-facing result.
	 * Keeping both lets a later diagnosis distinguish a missing prerequisite from an executor that received it but did
	 * not apply the prompt.
	 *
	 * @param runPath the persistent evaluation record
	 * @param caseId the testcase identifier
	 * @param trial the claimed whole-prompt trial number
	 * @param status the validated outcome
	 * @param rawResultPath the retained raw-result path
	 * @param validatorResultPath the retained validator-result path
	 * @param deterministicResultPath the optional retained deterministic-check receipt
	 * @throws IOException if the record is invalid or duplicates a trial
	 */
	static void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResultPath,
		Path validatorResultPath, Path deterministicResultPath) throws IOException
	{
		record(runPath, caseId, trial, status, rawResultPath, validatorResultPath, deterministicResultPath, null);
	}

	/**
	 * Adds one validated result and its measured worker durations while owning the evaluation record.
	 *
	 * @param runPath the persistent result record
	 * @param caseId the testcase identifier
	 * @param trial the claimed whole-prompt trial number
	 * @param status the validated outcome
	 * @param rawResultPath the retained raw-result path
	 * @param validatorResultPath the retained validator-result path
	 * @param deterministicResultPath the optional retained deterministic-check receipt
	 * @param timing the optional measured worker timing
	 * @throws IOException if the record is invalid or duplicates a trial
	 */
	static void record(Path runPath, String caseId, int trial, AttemptStatus status, Path rawResultPath,
		Path validatorResultPath, Path deterministicResultPath, WorkerTiming timing) throws IOException
	{
		AttemptRecorder.record(runPath, caseId, trial, status, rawResultPath, validatorResultPath,
			deterministicResultPath, recorderTiming(timing));
	}

	/**
	 * Converts process-owned timing to the recorder's transport-only timing value.
	 *
	 * @param timing the optional worker timing
	 * @return the recorder timing, or {@code null} when no timing was measured
	 */
	private static AttemptRecorder.Timing recorderTiming(WorkerTiming timing)
	{
		if (timing == null)
			return null;
		return new AttemptRecorder.Timing(timing.queuedAtNanos(), timing.executionStartedAtNanos(),
			timing.commandStartedAtNanos(), timing.commandCompletedAtNanos(),
			timing.validationStartedAtNanos(), timing.validationCompletedAtNanos(), timing.mergedAtNanos());
	}

	/**
	 * Computes the current whole-prompt SPRT check and sequential confidence certification decision.
	 *
	 * @param runPath the persistent result record
	 * @return the current decision
	 * @throws IOException if the record or suite is invalid
	 */
	public static ObjectNode decide(Path runPath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Decision.update(scope.json(), runPath, true, decisionAccess());
		}
	}

	/**
	 * Determines whether an evaluation decision no longer permits trial execution.
	 *
	 * @param decision the recorded overall decision
	 * @return whether the decision is terminal
	 */
	public static boolean isTerminalDecision(String decision)
	{
		return switch (decision)
		{
			case "PASS", "FAIL", "INCONCLUSIVE", "INFRASTRUCTURE_FAILED" -> true;
			default -> false;
		};
	}

	/**
	 * Determines whether one smoke record retained its sole completed testcase result.
	 *
	 * @param run the persistent evaluation record
	 * @return whether the record is terminal without being certification evidence
	 */
	public static boolean hasCompletedSmoke(ObjectNode run)
	{
		return run.path("mode").asString().equals("smoke") && run.path("smoke_result").isObject();
	}

	/**
	 * Requires a smoke receipt to have passed for the exact candidate and execution conditions that a later
	 * regression check will exercise.
	 *
	 * @param smokeRunPath the retained smoke evaluation record
	 * @param regressionRunPath the prepared regression evaluation record
	 * @throws IOException if the smoke receipt is incomplete, failed, or describes different inputs
	 */
	private static void requireSuccessfulComparableSmoke(Path smokeRunPath, Path regressionRunPath) throws IOException
	{
		Path normalizedSmokeRunPath = smokeRunPath.toAbsolutePath().normalize();
		Path normalizedRegressionRunPath = regressionRunPath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalizedSmokeRunPath, NOFOLLOW_LINKS))
			throw new IOException("Regression check requires a retained smoke run: " + normalizedSmokeRunPath);
		ObjectNode smokeRun = object(read(normalizedSmokeRunPath), "smoke evaluation record");
		if (!hasCompletedSmoke(smokeRun))
			throw new IOException("Regression check requires a completed smoke run: " + normalizedSmokeRunPath);
		ObjectNode smoke = object(smokeRun.path("smoke_result"), "smoke result");
		String caseId = text(smoke, "case_id", normalizedSmokeRunPath);
		int trial = smoke.path("trial").asInt();
		boolean passed = false;
		for (JsonNode attempt : attempts(smokeRun, normalizedSmokeRunPath))
		{
			if (attempt.path("case_id").asString().equals(caseId) && attempt.path("trial").asInt() == trial &&
				attempt.path("status").asString().equals("passed"))
			{
				passed = true;
				break;
			}
		}
		if (!passed)
		{
			throw new IOException("Regression check requires a passed smoke result for " + caseId + " trial " + trial +
				": " + normalizedSmokeRunPath);
		}
		ObjectNode regressionRun = object(read(normalizedRegressionRunPath), "prepared regression evaluation record");
		for (String field : List.of("suite_sha256", "prompt_sha256", "execution_context_sha256",
			"candidate_identity_sha256", "assembled_prompt_route_sha256", "execution_identity_sha256"))
		{
			if (!text(smokeRun, field, normalizedSmokeRunPath).equals(text(regressionRun, field,
				normalizedRegressionRunPath)))
			{
				throw new IOException("Smoke run does not match the regression candidate: " + field);
			}
		}
		if (!text(smokeRun, "harness", normalizedSmokeRunPath).equals(text(regressionRun, "harness",
			normalizedRegressionRunPath)))
		{
			throw new IOException("Smoke run does not match the regression candidate: harness");
		}
	}

	/**
	 * Returns whether one record retained its complete candidate regression check.
	 *
	 * @param run the persistent evaluation record
	 * @return whether the record has one terminal regression-check result
	 */
	public static boolean hasCompletedRegressionCheck(ObjectNode run)
	{
		return run.path("mode").asString().equals("regression_check") && run.path("regression_check").isObject();
	}

	/**
	 * Returns whether one diagnostic record retained its complete routing-matrix result.
	 *
	 * @param run the persistent evaluation record
	 * @return whether the diagnostic reached a terminal matrix result
	 */
	public static boolean hasCompletedRoutingMatrix(ObjectNode run)
	{
		return run.path("mode").asString().equals("routing_matrix") && run.path("routing_matrix").isObject();
	}

	/**
	 * Returns whether one record retained the terminal reviewer-control result.
	 *
	 * @param run the persistent evaluation record
	 * @return whether the reviewer-control operation completed
	 */
	public static boolean hasCompletedReviewerControls(ObjectNode run)
	{
		return run.path("mode").asString().equals("reviewer_controls") && run.path("reviewer_controls").isObject();
	}

	/**
	 * Classifies a retained failed testcase without launching another model turn.
	 *
	 * <p>
	 * The command uses an explicit class emitted by a deterministic checker or validator when one exists. It does not
	 * infer a prompt failure from an opaque failed artifact: that result remains unresolved until its owner supplies
	 * evidence that distinguishes routing, application, tooling, prerequisites, or infrastructure.
	 *
	 * @param runPath the retained evaluation record
	 * @param caseId the stable testcase identifier
	 * @param trial the completed trial number
	 * @return the established class and the evidence artifact that established it
	 * @throws IOException if the requested attempt is absent or not failed
	 */
	static ObjectNode classifyFailure(Path runPath, String caseId, int trial) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return FailureClassifier.classify(scope.json(), runPath, caseId, trial, new FailureClassifier.Access()
		{
			@Override
			public ObjectNode readRun(Path path) throws IOException
			{
				return object(read(path), "evaluation record");
			}

			@Override
			public ArrayNode attempts(ObjectNode run, Path path) throws IOException
			{
				return PromptEvaluation.attempts(run, path);
			}

			@Override
			public ObjectNode testCase(ObjectNode run, Path path, String id) throws IOException
			{
				return testcase(run, path, id);
			}

			@Override
			public Path requiredArtifact(String value, String description) throws IOException
			{
				return PromptEvaluation.requiredArtifact(value, description);
			}

			@Override
			public Path routingEvidence(ObjectNode run, Path path, ObjectNode attempt) throws IOException
			{
				return missingOrganicRouteEvidence(run, path, attempt);
			}

			@Override
			public FailureClass semanticClass(ObjectNode run, Path path, ObjectNode attempt, Path result)
				throws IOException
			{
				return semanticFailureClass(run, path, attempt, result);
			}
		});
		}
	}

	/**
	 * Determines whether an organically routed testcase omitted a required route action.
	 *
	 * <p>
	 * A model can produce an otherwise plausible task answer without reading the rule that governs it. The retained
	 * native event stream distinguishes that delivery failure from a later failure to apply a delivered rule: it records
	 * each command the model actually invoked, while session-start context can merely mention the same rule path. This
	 * check uses only command events, so a route name embedded in context or a final answer cannot make an unread route
	 * appear selected.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the evaluation-record path
	 * @param attempt the failed testcase attempt
	 * @return the raw event stream that proves a required route was omitted or an excluded route was read, or
	 *     {@code null} when the attempt is not an organic route or the stream cannot establish selection
	 * @throws IOException if retained route metadata is malformed
	 */
	private static Path missingOrganicRouteEvidence(ObjectNode run, Path runPath, ObjectNode attempt) throws IOException
	{
		ObjectNode testCase = testcase(run, runPath, text(attempt, "case_id", runPath));
		JsonNode declaredExpectation = testCase.path("route_expectation");
		if (!declaredExpectation.isObject())
			return null;
		ObjectNode expectation = object(declaredExpectation, "route expectation");
		if (!expectation.path("mode").asString().equals("organic"))
			return null;
		Path rawResult = requiredArtifact(text(attempt, "raw_result", runPath), "raw result");
		try (ToolScope scope = new ToolScope())
		{
			if (!FailureClassifier.hasNativeEvents(scope.json(), rawResult))
				return null;
			ObjectNode result = RoutingEvidence.routeDeliveryCheck(scope.json(), testCase, rawResult, "routing-delivery",
				RoutingEvidence.candidateRoute(run, runPath));
			ObjectNode assertion = object(result.path("assertions").path("routing-delivery"),
				"routing-delivery assertion");
			if (text(assertion, "status", rawResult).equals("failed"))
				return rawResult;
		}
		return null;
	}

	/**
	 * Classifies a failed model-reviewed assertion after confirming that every deterministic prerequisite passed.
	 *
	 * <p>
	 * A validator verdict alone says only that the reader-facing result failed. When the testcase also retains a
	 * passed deterministic receipt for its mechanical prerequisites, a declared semantic failure class can establish
	 * application: the executor received the prerequisite and then produced the prohibited result. Without that receipt,
	 * the same verdict remains unresolved because a missing route or another prerequisite could have caused it.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the evaluation-record path
	 * @param attempt the failed testcase attempt
	 * @param evidence the retained validator verdict
	 * @return the declared semantic failure class, or {@code null} when the retained evidence cannot establish one
	 * @throws IOException if retained evidence is malformed
	 */
	private static FailureClass semanticFailureClass(ObjectNode run, Path runPath, ObjectNode attempt, Path evidence)
		throws IOException
	{
		ObjectNode testCase = testcase(run, runPath, text(attempt, "case_id", runPath));
		Path prompt = SuiteContract.requiredFile(path(text(run, "suite", runPath)), text(testCase, "prompt", runPath),
			"Test prompt", text(attempt, "case_id", runPath));
		Set<String> deterministicAssertions = SuiteContract.assertionIds(testCase, prompt,
			EvaluationKind.DETERMINISTIC);
		if (!deterministicAssertions.isEmpty())
		{
			if (!attempt.has("deterministic_result") || !attempt.has("deterministic_result_sha256"))
				return null;
			Path deterministicResult = requiredArtifact(text(attempt, "deterministic_result", runPath),
				"deterministic result");
			verifyFingerprint(deterministicResult, text(attempt, "deterministic_result_sha256", runPath),
				"Deterministic result changed after recording");
			if (DeterministicChecks.failed(deterministicResult, testCase, prompt))
				return null;
		}
		try (ToolScope scope = new ToolScope())
		{
			return FailureClassifier.semanticClass(scope.json(), testCase, evidence, attempt, runPath);
		}
	}

	/**
	 * Returns the declared testcase with the specified stable identifier.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the evaluation-record path
	 * @param caseId the testcase identifier
	 * @return the declared testcase
	 * @throws IOException if the suite does not contain the identifier
	 */
	static ObjectNode testcase(ObjectNode run, Path runPath, String caseId) throws IOException
	{
		Path suitePath = path(text(run, "suite", runPath));
		ObjectNode suite = SuiteContract.read(suitePath);
		for (JsonNode node : array(suite.path("cases"), "cases", suitePath))
		{
			ObjectNode testCase = object(node, "test case");
			if (text(testCase, "id", suitePath).equals(caseId))
				return testCase;
		}
		throw new IOException("Suite has no testcase " + caseId + ": " + suitePath);
	}

	/**
	 * Returns one stable evaluation summary and, when selected, the retained worker durations that produced it.
	 *
	 * @param runPath the persistent result record
	 * @param includePerformance whether to include measured queue, startup, execution, validation, and merge durations
	 * @return the requested completed evaluation summary
	 * @throws IOException if the record is not terminal or cannot verify its retained evidence
	 */
	private static ObjectNode report(Path runPath, boolean includePerformance) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Report.report(scope.json(), runPath, includePerformance, REPORT_ACCESS);
		}
	}

	/**
	 * Updates a decision from a batch that already verified its immutable inputs before claiming work.
	 *
	 * @param runPath the persistent result record
	 * @return the current decision
	 * @throws IOException if the record is invalid
	 */
	private static ObjectNode decideDuringBatch(Path runPath) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Decision.update(scope.json(), runPath, false, decisionAccess());
		}
	}

	/**
	 * Supplies the facade-owned record and evidence collaborators for {@link Decision}.
	 *
	 * @return the decision-projection collaborators
	 */
	private static Decision.Access decisionAccess()
	{
		return new Decision.Access()
		{
			@Override
			public ObjectNode withLock(Path runPath, Decision.LockedOperation operation) throws IOException
			{
				ObjectNode[] result = new ObjectNode[1];
				withRunLock(runPath, () -> result[0] = operation.run());
				return result[0];
			}

			@Override
			public ObjectNode read(Path runPath) throws IOException
			{
				return object(PromptEvaluation.read(runPath), "run");
			}

			@Override
			public void write(Path runPath, ObjectNode run) throws IOException
			{
				PromptEvaluation.write(runPath, run);
			}

			@Override
			public void validateInputs(ObjectNode run, Path runPath) throws IOException
			{
				validateRunInputs(run, runPath);
			}

			@Override
			public void validateEvidence(ObjectNode run, Path runPath) throws IOException
			{
				validateEvidenceIntegrity(run, runPath);
			}

			@Override
			public Decision.Input input(ObjectNode run, Path runPath) throws IOException
			{
				Path suitePath = path(text(run, "suite", runPath));
				ObjectNode suite = SuiteContract.read(suitePath);
				Statistics.Parameters parameters = statisticsParameters(object(suite.path("sprt"), "sprt"), suitePath);
				List<SuiteOutcome> outcomes = suiteOutcomes(run, suite, runPath);
				boolean interrupted = interrupted(runPath);
				boolean infrastructureFailure = interrupted || hasInfrastructureFailure(runPath);
				List<Decision.Outcome> decisionOutcomes = outcomes.stream().map(outcome ->
					new Decision.Outcome(outcome.passed())).toList();
				return new Decision.Input(parameters, decisionOutcomes, infrastructureFailure,
					hasRoutingDeliveryFailure(run, runPath), Benchmark.inProgress(run, outcomes.size(), infrastructureFailure));
			}

			@Override
			public boolean interrupted(Path runPath)
			{
				return Files.isRegularFile(interruptionMarker(runPath));
			}

			@Override
			public boolean fixedBenchmarkInProgress(ObjectNode run, int completeTrials, boolean infrastructureFailure)
				throws IOException
			{
				return Benchmark.inProgress(run, completeTrials, infrastructureFailure);
			}
		};
	}

	/**
	 * Compares two records only when their suite and execution conditions match.
	 *
	 * @param baselinePath the earlier result record
	 * @param candidatePath the later result record
	 * @param diagnostic whether to return incompatible input fields instead of rejecting them
	 * @return the comparable measurements, or the incompatible input fields in diagnostic mode
	 * @throws IOException if the records are incomparable
	 */
	private static ObjectNode compare(Path baselinePath, Path candidatePath, boolean diagnostic) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return Comparison.compare(new Comparison.Request(baselinePath, candidatePath, diagnostic), new Comparison.Access()
		{
			@Override
			public ObjectNode read(Path path) throws IOException
			{
				return object(PromptEvaluation.read(path), "evaluation record");
			}

			@Override
			public void validateInputs(ObjectNode run, Path path) throws IOException
			{
				validateRunInputs(run, path);
			}

			@Override
			public void validateEvidence(ObjectNode run, Path path) throws IOException
			{
				validateEvidenceIntegrity(run, path);
			}

			@Override
			public ObjectNode decide(Path path) throws IOException
			{
				return PromptEvaluation.decide(path);
			}

			@Override
			public String candidateIdentity(ObjectNode run, Path path) throws IOException
			{
				return PromptEvaluation.candidateIdentity(run, path);
			}

			@Override
			public long longValue(ObjectNode run, String field, Path path) throws IOException
			{
				return PromptEvaluation.longValue(run, field, path);
			}
			}, scope.json());
		}
	}

	/**
	 * Counts completed diagnostic outcomes without assigning them a compliance meaning.
	 *
	 * @param run the retained evaluation record
	 * @param runPath the evaluation-record path
	 * @return the completed, failed, and unrun trial totals
	 * @throws IOException if a persisted outcome is invalid
	 */
	private static ObjectNode outcomeCounts(ObjectNode run, Path runPath) throws IOException
	{
		ObjectNode suite = SuiteContract.read(path(text(run, "suite", runPath)));
		List<SuiteOutcome> outcomes = suiteOutcomes(run, suite, runPath);
		int passed = (int) outcomes.stream().filter(SuiteOutcome::passed).count();
		ObjectNode counts = newObject();
		counts.put("passed", passed);
		counts.put("failed", outcomes.size() - passed);
		counts.put("unrun", incompleteSuiteRuns(run, suite, runPath));
		return counts;
	}

	/**
	 * Determines whether a record has already reached a terminal evaluation result.
	 *
	 * @param run the evaluation record
	 * @param suitePath the testcase-suite path
	 * @param runPath the evaluation-record path
	 * @return whether later workers must not reserve additional trials
	 * @throws IOException if the record or suite is invalid
	 */
	static boolean isTerminal(ObjectNode run, Path suitePath, Path runPath) throws IOException
	{
		ObjectNode suite = SuiteContract.read(suitePath);
		Statistics.Parameters parameters = statisticsParameters(
			object(suite.path("sprt"), "sprt"), suitePath);
		List<SuiteOutcome> outcomes = suiteOutcomes(run, suite, runPath);
		boolean infrastructureFailure = hasInfrastructureFailure(runPath);
		if (Benchmark.inProgress(run, outcomes.size(), infrastructureFailure))
			return false;
		List<Decision.Outcome> decisionOutcomes = outcomes.stream().map(outcome -> new Decision.Outcome(outcome.passed())).
			toList();
		try (ToolScope scope = new ToolScope())
		{
			return Decision.isTerminal(scope.json(), new Decision.Input(parameters, decisionOutcomes, infrastructureFailure,
				hasRoutingDeliveryFailure(run, runPath), false));
		}
	}

	/**
	 * Returns whether a failed organic route prevented the executor from receiving its required prompt contract.
	 *
	 * @param run the evaluation record
	 * @param runPath the record source
	 * @return whether an attempt omitted or selected a forbidden required route
	 * @throws IOException if retained route evidence is invalid
	 */
	private static boolean hasRoutingDeliveryFailure(ObjectNode run, Path runPath) throws IOException
	{
		for (JsonNode node : attempts(run, runPath))
		{
			ObjectNode attempt = object(node, "attempt");
			if (hasRoutingDeliveryFailure(run, runPath, attempt))
				return true;
		}
		return false;
	}

	/**
	 * Returns whether one failed attempt establishes a routing-delivery failure.
	 *
	 * @param run the evaluation record
	 * @param runPath the record source
	 * @param trial the whole-prompt trial number
	 * @return whether a failed attempt in the trial established routing delivery as the cause
	 * @throws IOException if retained evidence is unavailable
	 */
	private static boolean hasRoutingDeliveryFailure(ObjectNode run, Path runPath, int trial) throws IOException
	{
		for (JsonNode node : attempts(run, runPath))
		{
			ObjectNode attempt = object(node, "attempt");
			if (attempt.path("trial").asInt() == trial && hasRoutingDeliveryFailure(run, runPath, attempt))
				return true;
		}
		return false;
	}

	/**
	 * Returns whether one failed attempt establishes a routing-delivery failure.
	 *
	 * @param run the evaluation record
	 * @param runPath the record source
	 * @param attempt the retained testcase attempt
	 * @return whether the attempt's deterministic or native route evidence establishes the failure
	 * @throws IOException if retained evidence is unavailable
	 */
	private static boolean hasRoutingDeliveryFailure(ObjectNode run, Path runPath, ObjectNode attempt)
		throws IOException
	{
		if (AttemptStatus.parse(text(attempt, "status", runPath)) != AttemptStatus.FAILED)
			return false;
		Path evidence = requiredArtifact(text(attempt, "validator_result", runPath), "validator result");
		try (ToolScope scope = new ToolScope())
		{
			FailureClass classification = FailureClassifier.deterministicClass(scope.json(),
				testcase(run, runPath, text(attempt, "case_id", runPath)), evidence, runPath);
			return classification == FailureClass.ROUTING_DELIVERY ||
				missingOrganicRouteEvidence(run, runPath, attempt) != null;
		}
	}

	/**
	 * Determines whether a benchmark operation failed before it could produce a valid prompt-behavior result.
	 *
	 * @param runPath the persistent evaluation record
	 * @return whether the record contains blocked or unrun infrastructure evidence
	 * @throws IOException if the record is invalid
	 */
	private static boolean hasInfrastructureFailure(Path runPath) throws IOException
	{
		ObjectNode run = object(read(runPath), "run");
		for (JsonNode attempt : attempts(run, runPath))
		{
			AttemptStatus status = AttemptStatus.parse(text(object(attempt, "trial record"), "status", runPath));
			if (status == AttemptStatus.BLOCKED || status == AttemptStatus.UNRUN)
				return true;
		}
		return false;
	}

	/**
	 * Returns the durable marker written when the evaluator exits before recording its terminal result.
	 *
	 * @param runPath the evaluation record
	 * @return the adjacent interruption marker
	 */
	static Path interruptionMarker(Path runPath)
	{
		return EvaluationWorkspaceLifecycle.interruptionMarker(runPath);
	}

	/**
	 * Determines whether a suite defines one testcase.
	 *
	 * @param suite the suite manifest
	 * @param caseId the identifier to find
	 * @return whether the testcase is defined
	 */
	public static boolean containsCase(ObjectNode suite, String caseId)
	{
		for (JsonNode testCase : (ArrayNode) suite.path("cases"))
			if (testCase.path("id").asString().equals(caseId))
				return true;
		return false;
	}

	/**
	 * Collapses testcase attempts with the same trial number into whole-prompt outcomes. One independently validated
	 * failure conclusively fails the conjunction; a pass requires every testcase to pass. Canceled work is retained for
	 * recovery visibility but does not invent a compliance outcome.
	 *
	 * @param run the evaluation record
	 * @param suite the testcase suite
	 * @param runPath the evaluation-record path
	 * @return complete suite outcomes, ordered by trial number
	 * @throws IOException if a persisted status is invalid
	 */
	private static List<SuiteOutcome> suiteOutcomes(ObjectNode run, ObjectNode suite, Path runPath) throws IOException
	{
		Set<String> caseIds = new HashSet<>();
		for (JsonNode testCase : array(suite.path("cases"), "cases", runPath))
			caseIds.add(text(object(testCase, "test case"), "id", runPath));
		Map<Integer, Map<String, AttemptStatus>> statuses = trialStatuses(run, runPath);
		return Outcomes.complete(caseIds, statuses).stream().map(outcome -> new SuiteOutcome(outcome.trial(),
			outcome.passed())).toList();
	}

	/**
	 * Counts complete whole-prompt outcomes in one retained evaluation record.
	 *
	 * @param run the evaluation record
	 * @param suite the selected suite
	 * @param runPath the record path used in diagnostics
	 * @return the number of complete suite outcomes
	 * @throws IOException if the retained attempts are malformed
	 */
	public static int completeSuiteRunCount(ObjectNode run, ObjectNode suite, Path runPath) throws IOException
	{
		return suiteOutcomes(run, suite, runPath).size();
	}

	/**
	 * Returns the maximum number of complete suite outcomes allowed in one evaluation.
	 *
	 * @return the configured complete-suite limit
	 */
	public static int maximumSuiteRuns()
	{
		return MAXIMUM_SUITE_RUNS;
	}

	/**
	 * Counts started trial groups that did not produce a whole-prompt outcome because the evaluator stopped them before a
	 * validator or deterministic check could establish one.
	 *
	 * @param run the evaluation record
	 * @param suite the testcase suite
	 * @param runPath the evaluation-record path
	 * @return the incomplete whole-prompt trial count
	 * @throws IOException if a persisted status is invalid
	 */
	private static int incompleteSuiteRuns(ObjectNode run, ObjectNode suite, Path runPath) throws IOException
	{
		Set<String> caseIds = new HashSet<>();
		for (JsonNode testCase : array(suite.path("cases"), "cases", runPath))
			caseIds.add(text(object(testCase, "test case"), "id", runPath));
		int result = 0;
		for (Map.Entry<Integer, Map<String, AttemptStatus>> entry : trialStatuses(run, runPath).entrySet())
		{
			if (hasRoutingDeliveryFailure(run, runPath, entry.getKey()))
			{
				++result;
				continue;
			}
			Map<String, AttemptStatus> trial = entry.getValue();
			if (!trial.values().stream().anyMatch(status -> status == AttemptStatus.FAILED) &&
				(!trial.keySet().containsAll(caseIds) ||
					trial.values().stream().anyMatch(status -> status != AttemptStatus.PASSED)))
			{
				++result;
			}
		}
		return result;
	}

	/**
	 * Groups persisted attempts by their common whole-prompt trial number.
	 *
	 * @param run the evaluation record
	 * @param runPath the evaluation-record path
	 * @return statuses indexed by trial and testcase identifier
	 * @throws IOException if a persisted attempt is malformed
	 */
	private static Map<Integer, Map<String, AttemptStatus>> trialStatuses(ObjectNode run, Path runPath) throws IOException
	{
		Map<Integer, Map<String, AttemptStatus>> result = new TreeMap<>();
		for (Map.Entry<String, JsonNode> trialEntry : attemptIndex(run, runPath).properties())
		{
			int trial;
			try
			{
				trial = Integer.parseInt(trialEntry.getKey());
			}
			catch (NumberFormatException e)
			{
				throw new IOException("Attempt index has an invalid trial number: " + runPath, e);
			}
			if (trial < 1)
				throw new IOException("Attempt index has an invalid trial number: " + runPath);
			ObjectNode trialIndex = object(trialEntry.getValue(), "attempt index");
			Map<String, AttemptStatus> statuses = result.computeIfAbsent(trial, ignored -> new HashMap<>());
			for (Map.Entry<String, JsonNode> caseEntry : trialIndex.properties())
				if (statuses.put(caseEntry.getKey(), AttemptStatus.parse(caseEntry.getValue().asString())) != null)
					throw new IOException("Attempt index duplicates a testcase trial: " + caseEntry.getKey() + "/" + trial);
		}
		return result;
	}

	/**
	 * Verifies the immutable inputs that define an evaluation while workers append retained evidence.
	 *
	 * @param run the persisted evaluation record
	 * @param runPath the record's path
	 * @throws IOException if any retained input or result changed or disappeared
	 */
	static void validateRunInputs(ObjectNode run, Path runPath) throws IOException
	{
		RunIntegrity.verifyInputs(run, runPath);
	}

	/**
	 * Verifies every retained artifact once a terminal decision, comparison, or durable handoff needs complete evidence.
	 *
	 * @param run the persisted evaluation record
	 * @param runPath the record's path
	 * @throws IOException if retained evidence changed or disappeared
	 */
	public static void validateEvidenceIntegrity(ObjectNode run, Path runPath) throws IOException
	{
		RunIntegrity.verifyEvidence(run, runPath);
	}

	/**
	 * Reads the durable append-only attempt ledger in its recorded order.
	 *
	 * @param run the evaluation record
	 * @param runPath the record's path
	 * @return the parsed attempt records
	 * @throws IOException if the ledger is absent or malformed
	 */
	public static ArrayNode attempts(ObjectNode run, Path runPath) throws IOException
	{
		return AttemptLedger.read(run, runPath);
	}

	/**
	 * Returns the compact attempt lookup index.
	 *
	 * @param run the evaluation record
	 * @param runPath the evaluation-record path
	 * @return trial and testcase statuses indexed for lookup
	 * @throws IOException if the record omits its required attempt index
	 */
	public static ObjectNode attemptIndex(ObjectNode run, Path runPath) throws IOException
	{
		return AttemptLedger.index(run, runPath);
	}

	/**
	 * Durably appends one immutable attempt after all attempt artifacts have been fingerprinted.
	 *
	 * @param run the evaluation record
	 * @param runPath the record's path
	 * @param attempt the completed attempt
	 * @throws IOException if the record cannot be appended
	 */
	public static void appendAttempt(ObjectNode run, Path runPath, ObjectNode attempt) throws IOException
	{
		AttemptLedger.append(run, runPath, attempt);
	}

	/**
	 * Replaces an attempt ledger after an evaluator-owned durable-path rewrite.
	 *
	 * @param run the evaluation record
	 * @param runPath the record's path
	 * @param attempts the rewritten attempt records
	 * @throws IOException if the ledger cannot be atomically replaced
	 */
	public static void writeAttemptLedger(ObjectNode run, Path runPath, ArrayNode attempts) throws IOException
	{
		AttemptLedger.rewrite(run, runPath, attempts);
	}

	/**
	 * Compares one retained artifact with the fingerprint stored when it was accepted as evidence.
	 *
	 * @param artifact the retained artifact
	 * @param expectedFingerprint the recorded SHA-256 value
	 * @param message the failure prefix
	 * @throws IOException if the fingerprint does not match
	 */
	public static void verifyFingerprint(Path artifact, String expectedFingerprint, String message) throws IOException
	{
		if (!sha256(artifact).equals(expectedFingerprint))
			throw new IOException(message + ": " + artifact);
	}

	/**
	 * Resolves a retained trial artifact and rejects a missing result that would make the evaluation irreproducible.
	 *
	 * @param value the supplied artifact path
	 * @param description the artifact's reader-visible purpose
	 * @return the normalized regular-file path
	 * @throws IOException if the artifact is absent
	 */
	public static Path requiredArtifact(String value, String description) throws IOException
	{
		if (value.isBlank())
			throw new IOException("Missing " + description);
		Path result = path(value);
		if (!Files.isRegularFile(result))
			throw new IOException("Expected retained " + description + " file: " + result);
		return result;
	}

	/**
	 * Reads one non-negative JSON timestamp.
	 *
	 * @param value the containing JSON object
	 * @param field the field name
	 * @param source the source path
	 * @return the timestamp in epoch milliseconds
	 * @throws IOException if the timestamp is absent or invalid
	 */
	static long longValue(ObjectNode value, String field, Path source) throws IOException
	{
		if (!value.has(field) || !value.get(field).canConvertToLong() || value.get(field).asLong() < 0)
			throw new IOException("Missing non-negative timestamp " + field + ": " + source);
		return value.get(field).asLong();
	}

	/**
	 * Converts the suite's persisted statistical settings into the evaluator's focused decision policy.
	 *
	 * @param sprt the suite's sequential-test settings
	 * @param source the suite that declares the settings
	 * @return the complete fixed statistical policy
	 * @throws IOException if a setting is absent or invalid
	 */
	private static Statistics.Parameters statisticsParameters(ObjectNode sprt, Path source)
		throws IOException
	{
		return new Statistics.Parameters(SuiteContract.requiredCompliance(),
			SuiteContract.probability(sprt, "accept_pass_rate", source),
			SuiteContract.probability(sprt, "reject_pass_rate", source),
			SuiteContract.probability(sprt, "false_accept_rate", source),
			SuiteContract.probability(sprt, "false_reject_rate", source),
			MAXIMUM_SUITE_RUNS);
	}

	/**
	 * Requires one JSON array.
	 *
	 * @param value the candidate value
	 * @param name the field name
	 * @param source the source path
	 * @return the array value
	 * @throws IOException if the value is not an array
	 */
	public static ArrayNode array(JsonNode value, String name, Path source) throws IOException
	{
		if (!value.isArray())
			throw new IOException("Missing array " + name + ": " + source);
		return (ArrayNode) value;
	}

	/**
	 * Requires one JSON object.
	 *
	 * @param value the candidate value
	 * @param name the value description
	 * @return the object value
	 * @throws IOException if the value is not an object
	 */
	public static ObjectNode object(JsonNode value, String name) throws IOException
	{
		if (!value.isObject())
			throw new IOException("Expected object for " + name);
		return (ObjectNode) value;
	}

	/**
	 * Reads one runner-owned receipt as typed retained evidence.
	 *
	 * <p>
	 * The runner owns the receipt schema. Prompt evaluation owns only the decision that consumes the completed project
	 * and final response, so it delegates receipt validation instead of reproducing that schema at each execution path.
	 *
	 * @param receiptPath the runner receipt
	 * @param receiptName the receipt name used in diagnostics
	 * @return the retained project and raw response named by the receipt
	 * @throws IOException if the receipt is unavailable or malformed
	 */
	static HarnessEvaluationResult harnessResult(Path receiptPath, String receiptName) throws IOException
	{
		return HarnessRunReceipt.fromReceipt(object(read(receiptPath), receiptName), receiptPath);
	}

	/**
	 * Runs one declared executor or validator evaluation through the typed runner boundary.
	 *
	 * @param plan the execution plan that declares the runner and selected profile
	 * @param operation the declared executor, validator, or batched-validator operation
	 * @param project the isolated project supplied to the runner
	 * @param input the complete JSONL evaluation input
	 * @param transcript the durable bridge transcript destination
	 * @param receipt the durable retained-evidence receipt destination
	 * @param readOnlyEvidence immutable evidence roots supplied to the runner
	 * @return the retained result and native-process timing
	 * @throws IOException if the plan is incomplete or the runner cannot retain the evaluation result
	 */
	public static HarnessEvaluationExecution executeHarnessEvaluation(ObjectNode plan, String operation, Path project,
		String input, Path transcript, Path receipt, List<Path> readOnlyEvidence) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return executeHarnessEvaluation(plan, operation, project, input, transcript, receipt, readOnlyEvidence,
				new NativeHarnessEvaluationRunner(scope.json()));
		}
	}

	/**
	 * Runs one declared evaluation through the supplied runner after constructing its typed request from the plan.
	 *
	 * @param plan the execution plan that declares the runner and selected profile
	 * @param operation the declared executor, validator, or batched-validator operation
	 * @param project the isolated project supplied to the runner
	 * @param input the complete JSONL evaluation input
	 * @param transcript the durable bridge transcript destination
	 * @param receipt the durable retained-evidence receipt destination
	 * @param readOnlyEvidence immutable evidence roots supplied to the runner
	 * @param runner the runner that executes the typed request
	 * @return the retained result and native-process timing
	 * @throws IOException if the plan is incomplete or the runner cannot retain the evaluation result
	 */
	public static HarnessEvaluationExecution executeHarnessEvaluation(ObjectNode plan, String operation, Path project,
		String input, Path transcript, Path receipt, List<Path> readOnlyEvidence, HarnessEvaluationRunner runner)
		throws IOException
	{
		Path planPath = Path.of("execution plan");
		ObjectNode identity = object(plan.path("identity"), "execution identity");
		ObjectNode entry = object(plan.path(operation), operation + " plan");
		String profile = switch (operation)
		{
			case "executor" -> "executor";
			case "validator", "validator_batch" -> "validator";
			default -> throw new IOException("Unsupported harness evaluation operation: " + operation);
		};
		JsonNode installCat = entry.path("install_cat");
		if (!installCat.isBoolean())
			throw new IOException("Execution plan has no install_cat capability: " + operation);
		Path launcher = Identity.resolvePlanPath(text(identity, "runner_launcher", planPath), planPath);
		Path artifact = Identity.resolvePlanPath(text(identity, "runner_artifact", planPath), planPath);
		HarnessEvaluationRequest request = new HarnessEvaluationRequest(launcher, artifact, project,
			text(identity, profile + "_model", planPath), text(identity, profile + "_effort", planPath), input,
			transcript, receipt,
			installCat.asBoolean(), readOnlyEvidence);
		return runner.execute(request);
	}

	/**
	 * Reads one non-blank JSON string.
	 *
	 * @param object the containing object
	 * @param field the field name
	 * @param source the source path
	 * @return the string value
	 * @throws IOException if the field is absent or blank
	 */
	public static String text(ObjectNode object, String field, Path source) throws IOException
	{
		if (!object.hasNonNull(field) || !object.get(field).isString() || object.get(field).asString().isBlank())
			throw new IOException("Missing text " + field + ": " + source);
		return object.get(field).asString();
	}

/**
	 * Reads one JSON document.
	 *
	 * @param path the source path
	 * @return the parsed document
	 * @throws IOException if the source cannot be read
	 */

	public static JsonNode read(Path path) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return RecordStore.read(scope.json(), path);
		}
	}

	/**
	 * Normalizes one persisted path value.
	 *
	 * @param text the stored path text
	 * @return the normalized path
	 */
	public static Path path(String text)
	{
		return Path.of(text).toAbsolutePath().normalize();
	}

/**
	 * Atomically writes one evaluation record.
	 *
	 * @param destination the destination file
	 * @param contents the record contents
	 * @throws IOException if the record cannot be persisted
	 */

	public static void write(Path destination, ObjectNode contents) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			RecordStore.write(scope.json(), destination, contents);
		}
	}

	/**
	 * Atomically writes one retained text record.
	 *
	 * @param destination the destination file
	 * @param contents the record contents
	 * @throws IOException if the record cannot be persisted
	 */
	public static void writeText(Path destination, String contents) throws IOException
	{
		RecordStore.writeText(destination, contents);
	}

	/**
	 * Executes one record transition without allowing concurrent workers to lose a completed result.
	 *
	 * @param runPath the persistent result record
	 * @param operation the transition to execute
	 * @throws IOException if the record cannot be locked or updated
	 */
	public static void withRunLock(Path runPath, IoOperation operation) throws IOException
	{
		RecordStore.withLock(runPath, operation::run);
	}

	/**
	 * Hashes one artifact for comparability checks.
	 *
	 * @param file the artifact file
	 * @return its SHA-256 value
	 * @throws IOException if hashing fails
	 */
	public static String sha256(Path file) throws IOException
	{
		try
		{
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Hashes one UTF-8 value for a stable execution-identity field.
	 *
	 * @param value the value to hash
	 * @return its SHA-256 value
	 * @throws IOException if SHA-256 is unavailable
	 */
	public static String sha256(String value) throws IOException
	{
		try
		{
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

/**
	 * Serializes a JSON value through an operation-owned mapper.
	 *
	 * @param value the value to serialize
	 * @return the JSON representation
	 * @throws IOException if the value cannot be serialized
	 */

	public static String jsonText(Object value) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return scope.json().writeValueAsString(value);
		}
	}

/**
	 * Creates one evaluation JSON object through an operation-owned mapper.
	 *
	 * @return an empty mutable JSON object
	 */

	public static ObjectNode newObject()
	{
		try (ToolScope scope = new ToolScope())
		{
			return scope.json().createObjectNode();
		}
	}

/**
	 * Creates one evaluation JSON array through an operation-owned mapper.
	 *
	 * @return an empty mutable JSON array
	 */

	public static ArrayNode newArray()
	{
		try (ToolScope scope = new ToolScope())
		{
			return scope.json().createArrayNode();
		}
	}

/**
	 * Parses one JSON value through an operation-owned mapper.
	 *
	 * @param text the JSON text
	 * @return the parsed JSON value
	 * @throws IOException if the text is malformed
	 */

	public static JsonNode parseJson(String text) throws IOException
	{
		try (ToolScope scope = new ToolScope())
		{
			return scope.json().readTree(text);
		}
	}

	/**
	 * Hashes a declared identity artifact, including all regular files in a directory.
	 *
	 * @param artifact the regular file or directory
	 * @return the content fingerprint
	 * @throws IOException if hashing fails
	 */
	public static String fingerprintArtifact(Path artifact) throws IOException
	{
		if (Files.isRegularFile(artifact))
			return sha256(artifact);
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateFixtureDigest(digest, "artifact", artifact);
			return java.util.HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Hashes every persistent file that defines one test suite.
	 *
	 * @param suitePath the scenario-suite root
	 * @return the SHA-256 fingerprint of every persistent scenario-suite file
	 * @throws IOException if the suite cannot be read
	 */
	public static String suiteFingerprint(Path suitePath) throws IOException
	{
		suitePath = suitePath.toAbsolutePath().normalize();
		SuiteContract.read(suitePath);
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateFixtureDigest(digest, "scenario-suite", suitePath);
			return java.util.HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Adds one persistent file and its stable suite-relative label to a suite fingerprint.
	 *
	 * @param digest the fingerprint digest
	 * @param label the stable file label
	 * @param file the persistent file
	 * @throws IOException if the file cannot be read
	 */
	public static void updateDigest(MessageDigest digest, String label, Path file) throws IOException
	{
		digest.update(label.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
		try (InputStream input = Files.newInputStream(file))
		{
			byte[] buffer = new byte[8_192];
			int read = input.read(buffer);
			while (read != -1)
			{
				digest.update(buffer, 0, read);
				read = input.read(buffer);
			}
		}
	}

	/**
	 * Adds every file in one declared fixture to a suite fingerprint.
	 *
	 * @param digest the fingerprint digest
	 * @param label the stable fixture label
	 * @param fixture the persistent fixture directory
	 * @throws IOException if the fixture cannot be read
	 */
	public static void updateFixtureDigest(MessageDigest digest, String label, Path fixture) throws IOException
	{
		try (Stream<Path> paths = Files.walk(fixture))
		{
			for (Path file : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList())
				updateDigest(digest, label + "/" + fixture.relativize(file), file);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private PromptEvaluation()
	{
	}

	/**
	 * Runs one record transition.
	 */
	@FunctionalInterface
	public interface IoOperation
	{
		/**
		 * Runs the transition.
		 *
		 * @throws IOException if the transition fails
		 */
		void run() throws IOException;
	}

	/**
	 * Produces one value while allowing a checked filesystem failure.
	 *
	 * @param <T> the produced value type
	 */
	@FunctionalInterface
	private interface IoSupplier<T>
	{
		/**
		 * Produces the value.
		 *
		 * @return the produced value
		 * @throws IOException if production fails
		 */
		T get() throws IOException;
	}
}
