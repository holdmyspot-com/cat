package io.github.cowwoc.cat.common.optimization;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.optimization.certification.CandidateAllowanceLedger;
import io.github.cowwoc.cat.common.optimization.certification.CertificationCommand;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialDecision;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialEngine;
import io.github.cowwoc.cat.common.optimization.certification.FamilySequentialPolicyDocument;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialLedger;
import io.github.cowwoc.cat.common.optimization.certification.FormalTrialOperation;
import io.github.cowwoc.cat.common.runner.PromptInput;
import io.github.cowwoc.cat.common.optimization.certification.Statistics;
import io.github.cowwoc.cat.common.optimization.contract.CertificationContract;
import io.github.cowwoc.cat.common.optimization.development.PromptTestContractCatalog;
import io.github.cowwoc.cat.common.optimization.development.FormalPromptTestScheduler;
import io.github.cowwoc.cat.common.optimization.development.PromptTestGenerationPolicy;
import io.github.cowwoc.cat.common.optimization.development.PromptTestPoolPartition;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationExecution;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationRequest;
import io.github.cowwoc.cat.common.runner.HarnessEvaluationResult;
import io.github.cowwoc.cat.common.runner.HarnessModelProfile;
import io.github.cowwoc.cat.common.runner.ProviderUsage;
import io.github.cowwoc.cat.common.runner.ReasoningEffort;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests durable single-operation certification-process execution.
 */
public final class CertificationProcessExecutorTest
{
	private static final String CONTRACT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String ENVIRONMENT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

	/**
	 * Creates the test fixture.
	 */
	public CertificationProcessExecutorTest()
	{
	}

	/**
	 * Executes a fixture-selected terminal operation, persists its receipt, and advances the ledger once.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void executesTerminalOperation() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("executor-terminal-"));
			Path manifest = process.resolve("certification-process.json");
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.initializeForFixture(json, manifest, "executor-terminal", CONTRACT, ENVIRONMENT,
				OperationKind.FINISH_CERTIFICATION_PROCESS);
			Path evidence = Files.writeString(process.resolve("result.json"), "certified");
			CertificationProcessExecutor executor = new CertificationProcessExecutor(handlers(evidence, new AtomicInteger()),
				inputResolver(),
				UUID.fromString("11111111-1111-1111-1111-111111111111"), 123);

			CertificationProcessExecutor.ExecutionResult result = executor.run(json, CLOCK, process, CONTRACT,
				ENVIRONMENT);

			requireThat(result.terminalState(), "terminalState").isEqualTo(CertificationProcessState.CERTIFIED);
			requireThat(result.finalSequence(), "finalSequence").isEqualTo(1L);
			requireThat(result.receipts().size(), "receiptCount").isEqualTo(1);
			requireThat(CertificationProcessLedger.read(json, manifest).state().terminalResult(), "ledgerTerminal").
				contains(OperationResultKind.CERTIFIED);
		}
	}

	/**
	 * Verifies recovery records a completed operation receipt published before the process ledger advanced without
	 * executing its handler again.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void replaysCompletedReceiptWithoutHandler() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("executor-recover-receipt-"));
			Path manifest = process.resolve("certification-process.json");
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.initializeForFixture(json, manifest, "executor-recover-receipt", CONTRACT,
				ENVIRONMENT, OperationKind.FINISH_CERTIFICATION_PROCESS);
			Path evidence = Files.writeString(process.resolve("result.json"), "certified");
			Path operationPath = OperationDirectory.path(process, 1, OperationKind.FINISH_CERTIFICATION_PROCESS, 1);
			OptimizationContext context = new OptimizationContext(json, CLOCK, process, operationPath,
				OperationKind.FINISH_CERTIFICATION_PROCESS, CONTRACT, ENVIRONMENT);
			OptimizationInput input = inputResolver().resolve(context, 1, Optional.empty());
			OperationDirectory directory = OperationDirectory.create(json, CLOCK, context, 1, 1, input);
			directory.start(input.inputFingerprint());
			directory.complete(new OptimizationResult(OperationKind.FINISH_CERTIFICATION_PROCESS,
				OperationResultKind.CERTIFIED, evidence, Fingerprints.ofFile(evidence), Optional.empty()));
			AtomicInteger handlerInvocations = new AtomicInteger();
			CertificationProcessExecutor executor = new CertificationProcessExecutor(handlers(evidence, handlerInvocations),
				inputResolver(), UUID.fromString("11111111-1111-1111-1111-111111111111"), 123);

			CertificationProcessExecutor.ExecutionResult result = executor.run(json, CLOCK, process, CONTRACT,
				ENVIRONMENT);

			requireThat(result.terminalState(), "terminalState").isEqualTo(CertificationProcessState.CERTIFIED);
			requireThat(result.finalSequence(), "finalSequence").isEqualTo(1L);
			requireThat(result.receipts().size(), "receiptCount").isEqualTo(1);
			requireThat(handlerInvocations.get(), "handlerInvocations").isEqualTo(0);
		}
	}

	/**
	 * Rejects a missing process manifest without creating operation state.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void rejectsMissingProcessManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("executor-missing-"));
			CertificationProcessExecutor executor = new CertificationProcessExecutor(handlers(
				Files.writeString(process.resolve("result.json"), "certified"), new AtomicInteger()), inputResolver(),
				UUID.fromString("11111111-1111-1111-1111-111111111111"), 123);

			requireIo(() -> executor.run(new JsonMapper(), CLOCK, process, CONTRACT, ENVIRONMENT));
			requireThat(Files.notExists(process.resolve("operations")), "operationsAbsent").isTrue();
		}
	}

	/**
	 * Retains one retryable failed attempt once, advances to the next attempt, and completes the selected process.
	 *
	 * @throws IOException if the test fixture cannot be used
	 */
	@Test
	public void retriesFailedOperationOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("executor-retry-"));
			Path manifest = process.resolve("certification-process.json");
			JsonMapper json = new JsonMapper();
			CertificationProcessLedger.initializeForFixture(json, manifest, "executor-retry", CONTRACT, ENVIRONMENT,
				OperationKind.SCHEDULE_TRIAL);
			Path evidence = Files.writeString(process.resolve("result.json"), "result");
			AtomicInteger scheduleAttempts = new AtomicInteger();
			CertificationProcessExecutor executor = new CertificationProcessExecutor(
				retryingHandlers(evidence, scheduleAttempts), inputResolver(),
				UUID.fromString("11111111-1111-1111-1111-111111111111"), 123);

			CertificationProcessExecutor.ExecutionResult result = executor.run(json, CLOCK, process, CONTRACT,
				ENVIRONMENT);

			requireThat(scheduleAttempts.get(), "scheduleAttempts").isEqualTo(2);
			requireThat(result.terminalState(), "terminalState").isEqualTo(CertificationProcessState.CERTIFIED);
			requireThat(result.receipts().size(), "receiptCount").isEqualTo(5);
			requireThat(result.receipts().get(0).state(), "failedState").isEqualTo(OperationState.FAILED);
			requireThat(result.receipts().get(0).attempt(), "failedAttempt").isEqualTo(1);
			requireThat(result.receipts().get(1).state(), "retryState").isEqualTo(OperationState.COMPLETED);
			requireThat(result.receipts().get(1).attempt(), "retryAttempt").isEqualTo(2);
		}
	}

	/**
	 * Verifies the certification fixture resolves its initial typed input from the immutable process identity before a
	 * handler can run.
	 *
	 * @throws IOException if the fixture JSON cannot be encoded
	 */
	@Test
	public void resolvesInitialVerticalSliceInput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("executor-vertical-input-"));
			OptimizationContext context = new OptimizationContext(new JsonMapper(), CLOCK, process,
				OperationDirectory.path(process, 1, OperationKind.ASSIGN_ALLOWANCE, 1),
				OperationKind.ASSIGN_ALLOWANCE, CONTRACT, ENVIRONMENT);

			VerticalSliceInput input = (VerticalSliceInput) verticalSliceInputResolver().resolve(context, 1,
				Optional.empty());

			requireThat(input.operationKind(), "operationKind").isEqualTo(OperationKind.ASSIGN_ALLOWANCE);
			requireThat(input.transitionSequence(), "transitionSequence").isEqualTo(1L);
			requireThat(input.predecessorOperationKind(), "predecessorOperationKind").isEmpty();
			requireThat(input.predecessorResultKind(), "predecessorResultKind").isEmpty();
			requireThat(input.predecessorEvidenceFingerprint(), "predecessorEvidenceFingerprint").isEmpty();
			requireThat(input.inputFingerprint(), "inputFingerprint").isEqualTo(Fingerprints.ofUtf8(
			"{\"schema_version\":1,\"operation_kind\":\"assign-allowance\",\"transition_sequence\":1," +
				"\"contract_sha256\":\"" + CONTRACT + "\",\"environment_sha256\":\"" + ENVIRONMENT +
				"\",\"predecessor_operation_kind\":null,\"predecessor_result_kind\":null," +
				"\"predecessor_evidence_sha256\":null}"));
		}
	}

	/**
	 * Rejects a transition whose retained predecessor result is not the result registered for that sequence.
	 *
	 * @throws IOException if the fixture evidence cannot be written
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Vertical slice predecessor result does not match transition sequence: 2.*")
	public void rejectsWrongPredecessor() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path process = cleanup.add(Files.createTempDirectory("executor-vertical-predecessor-"));
			Path evidence = Files.writeString(process.resolve("evidence.json"), "evidence");
			OptimizationContext context = new OptimizationContext(new JsonMapper(), CLOCK, process,
				OperationDirectory.path(process, 2, OperationKind.SCHEDULE_TRIAL, 1), OperationKind.SCHEDULE_TRIAL,
				CONTRACT, ENVIRONMENT);
			OptimizationResult predecessor = new OptimizationResult(OperationKind.ASSIGN_ALLOWANCE,
				OperationResultKind.CERTIFIED, evidence, Fingerprints.ofFile(evidence),
				Optional.of(OperationKind.SCHEDULE_TRIAL));

			verticalSliceInputResolver().resolve(context, 2, Optional.of(predecessor));
		}
	}

	/**
	 * Verifies the production allowance schedule and family-sequential engine require one final distinct pass before
	 * certification.
	 *
	 * @throws IOException if fixture evidence cannot be retained
	 */
	@Test
	public void requiresFinalFormalPassForCertification() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("executor-formal-evidence-"));
			JsonMapper json = new JsonMapper();
			Path policyPath = Files.writeString(root.resolve("statistical-policy.json"), statisticalPolicyJson());
			FamilySequentialPolicyDocument.Loaded policy = FamilySequentialPolicyDocument.read(json, policyPath);
			Path allowances = root.resolve("allowances.json");
			CandidateAllowanceLedger.initialize(json, allowances, "lineage-1", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(json, allowances,
				"candidate-1", CLOCK.instant());
			Path trials = root.resolve("trials.json");
			FormalTrialLedger.initialize(json, trials, new FormalTrialLedger.Identity("candidate-1",
				fingerprint("candidate"), CONTRACT, ENVIRONMENT, fingerprint("evaluator"), policy.fingerprint(),
				assignment.entryFingerprint()), List.of("family-1"));
			Path evidence = Files.createDirectory(root.resolve("evidence"));
			int fixedSampleThreshold = Statistics.minimumPassingTrials(1_000, assignment.allowance().doubleValue(),
				0.95);
			int requiredTrialCount = firstSequentialPassingTrial(assignment.allowance().doubleValue());
			for (int trial = 1; trial < requiredTrialCount; ++trial)
				recordPass(json, trials, evidence, trial);

			FamilySequentialDecision.Result before = FamilySequentialEngine.decide(json, allowances, trials, policy);

			requireThat(before.action(), "beforeAction").isEqualTo(FamilySequentialDecision.Action.SAMPLE_FAMILY);
			requireThat(before.familyId(), "beforeFamily").contains("family-1");
			requireThat(requiredTrialCount, "requiredTrialCount").isLessThanOrEqualTo(fixedSampleThreshold);
			FormalTrialOperation.Result finalTrial = executeFinalFormalPass(json, root, evidence, allowances, trials,
				policy, requiredTrialCount);
			FamilySequentialDecision.Result after = FamilySequentialEngine.decide(json, allowances, trials, policy);
			requireThat(finalTrial.retainedOutcome().outcome().verdict(), "finalVerdict").isEqualTo(
				FormalTrialLedger.Verdict.PASS);
			requireThat(after.action(), "afterAction").isEqualTo(FamilySequentialDecision.Action.CERTIFIED);
		}
	}

	/**
	 * Executes the complete typed certification process through every production ledger boundary.
	 *
	 * @throws IOException if the typed fixture cannot be executed
	 */
	@Test
	public void executesTypedCertificationProcess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("executor-certification-vertical-"));
			JsonMapper json = new JsonMapper();
			VerticalSliceFixture fixture = new VerticalSliceFixture(root, json);
			CertificationProcessLedger.initializeForFixture(json, root.resolve("certification-process.json"),
				"typed-certification", CONTRACT, ENVIRONMENT, OperationKind.ASSIGN_ALLOWANCE);
			CertificationProcessExecutor executor = new CertificationProcessExecutor(
				verticalSliceHandlers(fixture), verticalSliceInputResolver(),
				UUID.fromString("11111111-1111-1111-1111-111111111111"), 123);

			CertificationProcessExecutor.ExecutionResult result = executor.run(json, CLOCK, root, CONTRACT, ENVIRONMENT);

			requireThat(result.terminalState(), "terminalState").isEqualTo(CertificationProcessState.CERTIFIED);
			requireThat(result.finalSequence(), "finalSequence").isEqualTo(6L);
			requireThat(result.receipts().size(), "receiptCount").isEqualTo(6);
			requireThat(fixture.handlerEvidenceCount(), "handlerEvidenceCount").isEqualTo(6);
			requireThat(Files.isDirectory(fixture.formalStateBefore), "formalStateBefore").isTrue();
			requireThat(Files.isRegularFile(fixture.formalStateBefore.resolve("trials.json.outcomes.jsonl")),
				"formalStateBeforeOutcomes").isTrue();
			requireThat(Files.isDirectory(fixture.formalStateAfter), "formalStateAfter").isTrue();
			requireThat(Files.isRegularFile(fixture.formalStateAfter.resolve("trials.json.outcomes.jsonl")),
				"formalStateAfterOutcomes").isTrue();
			requireThat(Files.isRegularFile(fixture.terminalSummary), "terminalSummary").isTrue();
		}
	}

	/**
	 * Creates the one terminal-operation handler registry.
	 *
	 * @param evidence the retained result evidence
	 * @param invocations the handler invocation counter
	 * @return the handler registry
	 */
	private static OptimizationHandlers handlers(Path evidence, AtomicInteger invocations)
	{
		OptimizationHandler<OptimizationInput> handler = new OptimizationHandler<>()
		{
			@Override
			public OperationKind operationKind()
			{
				return OperationKind.FINISH_CERTIFICATION_PROCESS;
			}

			@Override
			public Class<OptimizationInput> inputType()
			{
				return OptimizationInput.class;
			}

			@Override
			public OptimizationResult execute(OptimizationContext context, OptimizationInput input) throws IOException
			{
				invocations.incrementAndGet();
				return new OptimizationResult(context.operationKind(), OperationResultKind.CERTIFIED, evidence,
					Fingerprints.ofFile(evidence), Optional.empty());
			}
		};
		return new OptimizationHandlers(Set.of(OperationKind.FINISH_CERTIFICATION_PROCESS), List.of(handler));
	}

	/**
	 * Creates the six typed handlers used by the complete certification fixture.
	 *
	 * @param fixture the shared fixture state
	 * @return exactly one typed handler for each transition
	 */
	private static OptimizationHandlers verticalSliceHandlers(VerticalSliceFixture fixture)
	{
		List<OptimizationHandler<?>> handlers = new ArrayList<>();
		for (OperationKind operation : List.of(OperationKind.ASSIGN_ALLOWANCE, OperationKind.SCHEDULE_TRIAL,
			OperationKind.RECORD_OUTCOME, OperationKind.DECIDE, OperationKind.REPORT,
			OperationKind.FINISH_CERTIFICATION_PROCESS))
		{
			handlers.add(new OptimizationHandler<VerticalSliceInput>()
			{
				@Override
				public OperationKind operationKind()
				{
					return operation;
				}

				@Override
				public Class<VerticalSliceInput> inputType()
				{
					return VerticalSliceInput.class;
				}

				@Override
				public OptimizationResult execute(OptimizationContext context, VerticalSliceInput input) throws IOException
				{
					return fixture.execute(context, input);
				}
			});
		}
		return new OptimizationHandlers(Set.of(OperationKind.ASSIGN_ALLOWANCE, OperationKind.SCHEDULE_TRIAL,
			OperationKind.RECORD_OUTCOME, OperationKind.DECIDE, OperationKind.REPORT,
			OperationKind.FINISH_CERTIFICATION_PROCESS), handlers);
	}

	/**
	 * Owns the files and production collaborators for the typed vertical slice.
	 */
	private static final class VerticalSliceFixture
	{
		private final JsonMapper json;
		private final Path root;
		private final Path contract;
		private final Path environment;
		private final Path evaluatorPolicy;
		private final Path statisticalPolicy;
		private final Path promptTestCatalog;
		private final Path allowanceManifest;
		private final Path formalState;
		private final Path formalSnapshots;
		private final Path formalManifest;
		private final Path project;
		private final Path artifact;
		private final Path assertions;
		private final Path formalEvidence;
		private final Path schedule;
		private final PromptTestGenerationPolicy policy;
		private final FamilySequentialPolicyDocument.Loaded loadedPolicy;
		private final PromptTestContractCatalog catalog;
		private int requiredTrialCount;
		private FormalPromptTestScheduler.ScheduledPromptTest scheduled;
		private Path formalStateBefore;
		private Path formalStateAfter;
		private Path decisionEvidence;
		private Path reportEvidence;
		private Path reportHandlerEvidence;
		private Path terminalSummary;

		/**
		 * Creates all independently fingerprinted fixture inputs and state directories.
		 *
		 * @param process the certification-process root
		 * @param json the fixture JSON mapper
		 * @throws IOException if fixture files cannot be created
		 */
		private VerticalSliceFixture(Path process, JsonMapper json) throws IOException
		{
			this.json = json;
			root = Files.createDirectories(process.resolve("fixture"));
			contract = Files.writeString(root.resolve("contract.json"), "{\"contract\":\"frozen\"}\n");
			environment = Files.writeString(root.resolve("environment.json"), "{\"environment\":\"frozen\"}\n");
			evaluatorPolicy = Files.writeString(root.resolve("evaluator-policy.json"), "{\"evaluator\":\"v1\"}\n");
			statisticalPolicy = Files.writeString(root.resolve("statistical-policy.json"), statisticalPolicyJson());
			loadedPolicy = FamilySequentialPolicyDocument.read(json, statisticalPolicy);
			policy = verticalPolicy();
			CertificationContract frozen = verticalContract(policy);
			PromptTestContractCatalog.Result bound = PromptTestContractCatalog.bind(frozen, List.of(policy));
			catalog = bound.catalog().orElseThrow();
			promptTestCatalog = Files.writeString(root.resolve("prompt-test-catalog.json"),
				"{\"family\":\"family-1\",\"policy\":\"formal-1\"}\n");
			Path state = Files.createDirectories(root.resolve("state"));
			allowanceManifest = Files.createDirectories(state.resolve("allowance")).resolve("allowance.json");
			formalState = Files.createDirectories(state.resolve("formal"));
			formalSnapshots = Files.createDirectories(state.resolve("formal-snapshots"));
			formalManifest = formalState.resolve("trials.json");
			formalEvidence = Files.createDirectories(formalState.resolve("evidence"));
			schedule = Files.createDirectories(state.resolve("schedule")).resolve("scheduled-prompt-test.json");
			project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("AGENTS.md"), "Always answer with OK.\n");
			Path plugin = Files.createDirectories(root.resolve("artifact/.codex-plugin"));
			artifact = plugin.getParent();
			Files.writeString(plugin.resolve("plugin.json"), "{}\n");
			Path evidence = Files.createDirectories(root.resolve("evidence"));
			assertions = Files.writeString(evidence.resolve("assertions.json"),
				"{\"assertion-1\":\"response equals OK\"}\n");
		}

		/**
		 * Dispatches one typed transition to its fixture implementation.
		 *
		 * @param context the operation context
		 * @param input the verified typed input
		 * @return the operation result
		 * @throws IOException if the transition cannot be completed
		 */
		private OptimizationResult execute(OptimizationContext context, VerticalSliceInput input) throws IOException
		{
			return switch (context.operationKind())
			{
				case ASSIGN_ALLOWANCE -> assign(context);
				case SCHEDULE_TRIAL -> schedule(context);
				case RECORD_OUTCOME -> recordOutcome(context);
				case DECIDE -> decide(context);
				case REPORT -> report(context);
				case FINISH_CERTIFICATION_PROCESS -> finish(context, input);
				default -> throw new IOException("Unexpected vertical-slice operation: " + context.operationKind());
			};
		}

		/**
		 * Assigns the candidate allowance and seeds the retained prior formal passes.
		 *
		 * @param context the operation context
		 * @return the assigned result
		 * @throws IOException if allowance or formal state cannot be written
		 */
		private OptimizationResult assign(OptimizationContext context) throws IOException
		{
			CandidateAllowanceLedger.initialize(json, allowanceManifest, "lineage-typed", new BigDecimal("0.05"),
				CandidateAllowanceLedger.Schedule.HALVING_V1);
			CandidateAllowanceLedger.Assignment assignment = CandidateAllowanceLedger.assign(json, allowanceManifest,
				"candidate-typed", CLOCK.instant());
			requiredTrialCount = firstSequentialPassingTrial(assignment.allowance().doubleValue());
			FormalTrialLedger.initialize(json, formalManifest, new FormalTrialLedger.Identity("candidate-typed",
				fingerprint("candidate-typed"), CONTRACT, ENVIRONMENT, fingerprint("evaluator-policy"),
				loadedPolicy.fingerprint(), assignment.entryFingerprint()), List.of("family-1"));
			for (int trial = 1; trial < requiredTrialCount; ++trial)
				recordPass(json, formalManifest, formalEvidence, trial);
			return result(context, OperationResultKind.ASSIGNED, List.of(
				new Entry("contract", contract), new Entry("environment", environment),
				new Entry("evaluator_policy", evaluatorPolicy), new Entry("statistical_policy", statisticalPolicy)),
				List.of(new Entry("allowance_state", allowanceManifest), new Entry("formal_state", formalState)));
		}

		/**
		 * Runs the production engine and formal scheduler for the final draw.
		 *
		 * @param context the operation context
		 * @return the scheduled result
		 * @throws IOException if statistical state or schedule cannot be retained
		 */
		private OptimizationResult schedule(OptimizationContext context) throws IOException
		{
			FamilySequentialDecision.Result decision = FamilySequentialEngine.decide(json, allowanceManifest, formalManifest,
				loadedPolicy);
			requireThat(decision.action(), "scheduleAction").isEqualTo(FamilySequentialDecision.Action.SAMPLE_FAMILY);
			FormalPromptTestScheduler.Result result = FormalPromptTestScheduler.schedule(decision, catalog,
				requiredTrialCount, "formal-run-" + requiredTrialCount, List.of());
			scheduled = result.scheduledPromptTest().orElseThrow();
			Files.writeString(schedule, scheduled.promptTest().renderedPrompt());
			return result(context, OperationResultKind.SCHEDULED, List.of(new Entry("allowance_state", allowanceManifest),
				new Entry("formal_state", formalState), new Entry("prompt_test_catalog", promptTestCatalog)),
				List.of(new Entry("scheduled_prompt_test", schedule)));
		}

		/**
		 * Executes one formal trial through the typed static runner boundary.
		 *
		 * @param context the operation context
		 * @return the recorded result
		 * @throws IOException if formal evidence cannot be retained
		 */
		private OptimizationResult recordOutcome(OptimizationContext context) throws IOException
		{
			formalStateBefore = snapshotFormalState("formal-state-before");
			Path transcript = context.operationDirectory().resolve("transcript.jsonl");
			Path receipt = context.operationDirectory().resolve("runner-receipt.json");
			Path rawResult = context.operationDirectory().resolve("raw-result.txt");
			HarnessEvaluationRequest request = new HarnessEvaluationRequest(context.operationDirectory().resolve("codex"),
				artifact, project, new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM),
				PromptInput.forPrompt(json, scheduled.promptTest().renderedPrompt()), transcript, receipt, false,
				List.of(context.operationDirectory()));
			FormalTrialOperation.Request formalRequest = new FormalTrialOperation.Request("formal-operation-" +
				requiredTrialCount, scheduled, request);
			Path evaluatorEvidence = context.operationDirectory().resolve("evaluator-evidence.json");
			FormalTrialOperation.Result formalResult = FormalTrialOperation.execute(json, CLOCK, allowanceManifest,
				formalManifest, loadedPolicy, formalRequest, ignored ->
				{
					Files.writeString(transcript, "assistant: OK\n");
					Files.writeString(rawResult, "OK");
					Files.writeString(receipt, "runner receipt\n");
					return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(project, rawResult), 10, 20,
						false, "codex-test", "cat-test", ProviderUsage.of(10, 0, 1), ReproductionState.AVAILABLE,
						Map.of("retained_project", Fingerprints.ofArtifact(project), "raw_result",
							Fingerprints.ofFile(rawResult), "transcript", Fingerprints.ofFile(transcript)));
				}, execution ->
				{
					if (!Files.readString(execution.result().rawResult()).equals("OK"))
						throw new IOException("Typed fixture evaluator received an unexpected response");
					Files.writeString(evaluatorEvidence, "{\"verdict\":\"pass\"}\n");
					return new FormalTrialOperation.Evaluation(FormalTrialLedger.Verdict.PASS,
						Fingerprints.ofFile(evaluatorEvidence));
				});
			requireThat(formalResult.retainedOutcome().outcome().verdict(), "formalVerdict").isEqualTo(
				FormalTrialLedger.Verdict.PASS);
			formalStateAfter = snapshotFormalState("formal-state-after");
			return result(context, OperationResultKind.RECORDED,
				List.of(new Entry("allowance_state", allowanceManifest), new Entry("artifact", artifact),
					new Entry("assertions", assertions), new Entry("formal_state_before", formalStateBefore),
					new Entry("project", project), new Entry("scheduled_prompt_test", schedule)),
				List.of(new Entry("evaluator_evidence", evaluatorEvidence), new Entry("formal_state_after", formalStateAfter),
					new Entry("harness_operation", receipt.resolveSibling(receipt.getFileName() + ".operation.json")),
					new Entry("runner_receipt", receipt), new Entry("transcript", transcript)));
		}

		/**
		 * Obtains and retains the production certification decision.
		 *
		 * @param context the operation context
		 * @return the certified result
		 * @throws IOException if the decision is not certified
		 */
		private OptimizationResult decide(OptimizationContext context) throws IOException
		{
			FamilySequentialDecision.Result decision = FamilySequentialEngine.decide(json, allowanceManifest, formalManifest,
				loadedPolicy);
			requireThat(decision.action(), "decisionAction").isEqualTo(FamilySequentialDecision.Action.CERTIFIED);
			decisionEvidence = context.operationDirectory().resolve("decision.json");
			ObjectNode value = json.createObjectNode();
			value.put("schema_version", 1);
			value.put("action", "certified");
			value.put("current_lower_bound", decision.certification().currentLowerBound());
			value.put("target", loadedPolicy.policy().decisionPolicy().target());
			value.putNull("selected_family");
			Files.writeString(decisionEvidence, json.writeValueAsString(value));
			return result(context, OperationResultKind.CERTIFIED, List.of(new Entry("allowance_state", allowanceManifest),
				new Entry("formal_state", formalState), new Entry("statistical_policy", statisticalPolicy)),
				List.of(new Entry("decision", decisionEvidence)));
		}

		/**
		 * Runs the packaged certification command and retains its report.
		 *
		 * @param context the operation context
		 * @return the reported result
		 * @throws IOException if the report cannot be verified
		 */
		private OptimizationResult report(OptimizationContext context) throws IOException
		{
			StringBuilder output = new StringBuilder();
			CertificationCommand.run(json, CLOCK, new String[]{"decide", allowanceManifest.toString(),
				formalManifest.toString(), statisticalPolicy.toString()}, output);
			reportEvidence = context.operationDirectory().resolve("report.txt");
			Files.writeString(reportEvidence, output);
			String report = output.toString();
			String expectedLowerBound = "Current lower bound: " + String.format(Locale.ROOT, "%.1f%%",
				FamilySequentialEngine.decide(json, allowanceManifest, formalManifest, loadedPolicy).
					certification().currentLowerBound() * 100);
			String expectedTarget = "Target: above " + String.format(Locale.ROOT, "%.0f%%",
				loadedPolicy.policy().decisionPolicy().target() * 100);
			if (!report.contains(expectedLowerBound) || !report.contains(expectedTarget))
				throw new IOException("Certification report does not match the decision scalars");
			return result(context, OperationResultKind.REPORTED_CERTIFIED, List.of(new Entry("allowance_state",
				allowanceManifest), new Entry("decision", decisionEvidence), new Entry("formal_state", formalState),
				new Entry("statistical_policy", statisticalPolicy)), List.of(new Entry("report", reportEvidence)));
		}

		/**
		 * Verifies the predecessor report and writes the terminal summary.
		 *
		 * @param context the operation context
		 * @param input the typed predecessor identity
		 * @return the terminal certified result
		 * @throws IOException if predecessor evidence does not match
		 */
		private OptimizationResult finish(OptimizationContext context, VerticalSliceInput input) throws IOException
		{
			if (!Fingerprints.ofFile(reportHandlerEvidence).equals(input.predecessorEvidenceFingerprint().orElseThrow()))
				throw new IOException("Report evidence does not match predecessor");
			if (!Files.isRegularFile(reportEvidence) || !Files.isRegularFile(decisionEvidence))
				throw new IOException("Certification report evidence is missing");
			terminalSummary = context.operationDirectory().resolve("terminal-summary.json");
			ObjectNode value = json.createObjectNode();
			value.put("schema_version", 1);
			value.put("terminal_state", "certified");
			value.put("decision_sha256", Fingerprints.ofFile(decisionEvidence));
			value.put("report_sha256", Fingerprints.ofFile(reportEvidence));
			Files.writeString(terminalSummary, json.writeValueAsString(value));
			return result(context, OperationResultKind.CERTIFIED, List.of(new Entry("decision", decisionEvidence),
				new Entry("report", reportEvidence)), List.of(new Entry("terminal_summary", terminalSummary)));
		}

		/**
		 * Writes strict handler evidence and creates its operation result.
		 *
		 * @param context the operation context
		 * @param kind the registered result kind
		 * @param inputs the input artifacts
		 * @param outputs the output artifacts
		 * @return the verified result
		 * @throws IOException if handler evidence cannot be written
		 */
		private OptimizationResult result(OptimizationContext context, OperationResultKind kind, List<Entry> inputs,
			List<Entry> outputs) throws IOException
		{
			Path evidence = context.operationDirectory().resolve("handler-evidence.json");
			ObjectNode value = json.createObjectNode();
			value.put("schema_version", 1);
			value.put("operation_kind", context.operationKind().externalValue());
			value.put("result_kind", kind.externalValue());
			writeEntries(value.putArray("inputs"), inputs);
			writeEntries(value.putArray("outputs"), outputs);
			Files.writeString(evidence, json.writeValueAsString(value));
			if (context.operationKind() == OperationKind.REPORT)
				reportHandlerEvidence = evidence;
			Set<OperationKind> successors = OptimizationOperationRegistry.successors(context.operationKind(), kind);
			return new OptimizationResult(context.operationKind(), kind, evidence, Fingerprints.ofFile(evidence),
				successors.stream().findFirst());
		}

		/**
		 * Writes sorted artifact entries and their current fingerprints.
		 *
		 * @param array the destination array
		 * @param entries the artifact entries
		 * @throws IOException if an artifact cannot be fingerprinted
		 */
		private void writeEntries(tools.jackson.databind.node.ArrayNode array, List<Entry> entries) throws IOException
		{
			entries.stream().sorted((a, b) -> a.id.compareTo(b.id)).forEach(entry ->
			{
				ObjectNode value = array.addObject();
				value.put("id", entry.id);
				value.put("path", entry.path.toAbsolutePath().normalize().toString());
				try
				{
					value.put("sha256", Fingerprints.ofArtifact(entry.path));
				}
				catch (IOException e)
				{
					throw new IllegalStateException(e);
				}
			});
		}

		/**
		 * Copies the complete formal state to retain a before/after evidence snapshot.
		 *
		 * @param name the snapshot name
		 * @return the retained snapshot
		 * @throws IOException if formal state cannot be copied
		 */
		private Path snapshotFormalState(String name) throws IOException
		{
			Path snapshot = formalSnapshots.resolve(name);
			try (Stream<Path> paths = Files.walk(formalState))
			{
				Iterator<Path> iterator = paths.sorted().iterator();
				while (iterator.hasNext())
				{
					Path source = iterator.next();
					Path destination = snapshot.resolve(formalState.relativize(source));
					if (Files.isDirectory(source))
						Files.createDirectories(destination);
					else
						Files.copy(source, destination);
				}
			}
			return snapshot;
		}

		/**
		 * Counts operation-owned handler evidence documents.
		 *
		 * @return the number of handler evidence documents
		 * @throws IOException if the operation tree cannot be scanned
		 */
		private int handlerEvidenceCount() throws IOException
		{
			try (Stream<Path> files = Files.walk(root.getParent().resolve("operations")))
			{
				return (int) files.filter(path -> path.getFileName().toString().equals("handler-evidence.json")).count();
			}
		}

		/**
		 * Names one handler evidence artifact.
		 *
		 * @param id the stable artifact identifier
		 * @param path the artifact path
		 */
		private record Entry(String id, Path path)
		{
		}
	}

	/**
	 * Creates the smallest registry path that exercises a retryable scheduled operation before certification completes.
	 *
	 * @param evidence the retained evidence for every completed fixture operation
	 * @param scheduleAttempts the observed schedule-handler invocations
	 * @return a complete registry for the fixture path
	 */
	private static OptimizationHandlers retryingHandlers(Path evidence, AtomicInteger scheduleAttempts)
	{
		Map<OperationKind, OperationResultKind> results = Map.of(
			OperationKind.SCHEDULE_TRIAL, OperationResultKind.NO_VALID_SCHEDULE,
			OperationKind.DECIDE, OperationResultKind.CERTIFIED,
			OperationKind.REPORT, OperationResultKind.REPORTED_CERTIFIED,
			OperationKind.FINISH_CERTIFICATION_PROCESS, OperationResultKind.CERTIFIED);
		List<OptimizationHandler<?>> handlers = new ArrayList<>();
		for (Map.Entry<OperationKind, OperationResultKind> entry : results.entrySet())
			handlers.add(fixtureHandler(entry.getKey(), entry.getValue(), evidence, scheduleAttempts));
		return new OptimizationHandlers(results.keySet(), handlers);
	}

	/**
	 * Creates one fixture handler whose first schedule attempt records a retryable infrastructure failure.
	 *
	 * @param operation the handler's operation
	 * @param result the registered result it returns after any injected failure
	 * @param evidence the retained evidence
	 * @param scheduleAttempts the observed schedule-handler invocations
	 * @return the typed fixture handler
	 */
	private static OptimizationHandler<OptimizationInput> fixtureHandler(OperationKind operation,
		OperationResultKind result, Path evidence, AtomicInteger scheduleAttempts)
	{
		return new OptimizationHandler<>()
		{
			@Override
			public OperationKind operationKind()
			{
				return operation;
			}

			@Override
			public Class<OptimizationInput> inputType()
			{
				return OptimizationInput.class;
			}

			@Override
			public OptimizationResult execute(OptimizationContext context, OptimizationInput input) throws IOException
			{
				if (operation == OperationKind.SCHEDULE_TRIAL && scheduleAttempts.incrementAndGet() == 1)
				{
					throw new OperationFailureException(OperationDirectory.FailureKind.INFRASTRUCTURE_FAILURE, true,
						"temporary local harness failure", null);
				}
				Set<OperationKind> successors = OptimizationOperationRegistry.successors(operation, result);
				Optional<OperationKind> successor = successors.stream().findFirst();
				return new OptimizationResult(operation, result, evidence, Fingerprints.ofFile(evidence), successor);
			}
		};
	}

	/**
	 * Creates input for the fixture-selected operation.
	 *
	 * @return the fixture input resolver
	 */
	private static OptimizationInputResolver inputResolver()
	{
		return (context, transitionSequence, predecessor) -> new OptimizationInput()
		{
			@Override
			public OperationKind operationKind()
			{
				return context.operationKind();
			}

			@Override
			public String inputFingerprint()
			{
				return "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
			}
		};
	}

	/**
	 * Resolves the exact typed inputs accepted by the complete certification vertical slice.
	 *
	 * @return the fixture-owned typed input resolver
	 */
	private static OptimizationInputResolver verticalSliceInputResolver()
	{
		return (context, transitionSequence, predecessor) ->
		{
			validateVerticalSliceTransition(context.operationKind(), transitionSequence, predecessor);
			Optional<OperationKind> predecessorOperation = predecessor.map(OptimizationResult::operationKind);
			Optional<OperationResultKind> predecessorResult = predecessor.map(OptimizationResult::resultKind);
			Optional<String> predecessorEvidence = predecessor.map(OptimizationResult::evidenceFingerprint);
			ObjectNode encoded = context.json().createObjectNode();
			encoded.put("schema_version", 1);
			encoded.put("operation_kind", context.operationKind().externalValue());
			encoded.put("transition_sequence", transitionSequence);
			encoded.put("contract_sha256", context.contractFingerprint());
			encoded.put("environment_sha256", context.environmentFingerprint());
			encoded.put("predecessor_operation_kind", predecessorOperation.map(OperationKind::externalValue).orElse(null));
			encoded.put("predecessor_result_kind", predecessorResult.map(OperationResultKind::externalValue).orElse(null));
			encoded.put("predecessor_evidence_sha256", predecessorEvidence.orElse(null));
			String fingerprint = Fingerprints.ofUtf8(context.json().writeValueAsString(encoded));
			return new VerticalSliceInput(context.operationKind(), transitionSequence, context.contractFingerprint(),
				context.environmentFingerprint(), predecessorOperation, predecessorResult, predecessorEvidence, fingerprint);
		};
	}

	/**
	 * Rejects a transition that does not belong to the six-operation certification fixture path.
	 *
	 * @param operation the selected operation
	 * @param sequence the positive transition sequence
	 * @param predecessor the verified preceding result
	 * @throws IOException if the transition does not match the fixture contract
	 */
	private static void validateVerticalSliceTransition(OperationKind operation, long sequence,
		Optional<OptimizationResult> predecessor) throws IOException
	{
		OperationKind expectedOperation = switch ((int) sequence)
		{
			case 1 -> OperationKind.ASSIGN_ALLOWANCE;
			case 2 -> OperationKind.SCHEDULE_TRIAL;
			case 3 -> OperationKind.RECORD_OUTCOME;
			case 4 -> OperationKind.DECIDE;
			case 5 -> OperationKind.REPORT;
			case 6 -> OperationKind.FINISH_CERTIFICATION_PROCESS;
			default -> throw new IOException("Vertical slice has no transition sequence: " + sequence);
		};
		if (operation != expectedOperation)
			throw new IOException("Vertical slice operation does not match transition sequence: " + sequence);
		if (sequence == 1 && predecessor.isEmpty())
			return;
		if (sequence == 1 || predecessor.isEmpty())
			throw new IOException("Vertical slice predecessor does not match transition sequence: " + sequence);
		OptimizationResult completed = predecessor.orElseThrow();
		OperationResultKind expectedResult = switch ((int) sequence)
		{
			case 2 -> OperationResultKind.ASSIGNED;
			case 3 -> OperationResultKind.SCHEDULED;
			case 4 -> OperationResultKind.RECORDED;
			case 5 -> OperationResultKind.CERTIFIED;
			case 6 -> OperationResultKind.REPORTED_CERTIFIED;
			default -> throw new AssertionError("Validated vertical-slice sequence has no predecessor result");
		};
		if (completed.resultKind() != expectedResult)
			throw new IOException("Vertical slice predecessor result does not match transition sequence: " + sequence);
	}

	/**
	 * Holds one canonical input for the six-operation certification fixture.
	 *
	 * @param operationKind the selected operation
	 * @param transitionSequence the positive transition sequence
	 * @param contractFingerprint the immutable contract fingerprint
	 * @param environmentFingerprint the immutable environment fingerprint
	 * @param predecessorOperationKind the preceding operation, when one exists
	 * @param predecessorResultKind the preceding result, when one exists
	 * @param predecessorEvidenceFingerprint the preceding evidence fingerprint, when one exists
	 * @param inputFingerprint the canonical input fingerprint
	 */
	private record VerticalSliceInput(OperationKind operationKind, long transitionSequence, String contractFingerprint,
		String environmentFingerprint, Optional<OperationKind> predecessorOperationKind,
		Optional<OperationResultKind> predecessorResultKind, Optional<String> predecessorEvidenceFingerprint,
		String inputFingerprint) implements OptimizationInput
	{
		/**
		 * Validates the immutable transition identity.
		 *
		 * @param operationKind the selected operation
		 * @param transitionSequence the positive transition sequence
		 * @param contractFingerprint the immutable contract fingerprint
		 * @param environmentFingerprint the immutable environment fingerprint
		 * @param predecessorOperationKind the preceding operation, when one exists
		 * @param predecessorResultKind the preceding result, when one exists
		 * @param predecessorEvidenceFingerprint the preceding evidence fingerprint, when one exists
		 * @param inputFingerprint the canonical input fingerprint
		 */
		private VerticalSliceInput
		{
			Objects.requireNonNull(operationKind, "operationKind must not be null");
			Objects.requireNonNull(predecessorOperationKind, "predecessorOperationKind must not be null");
			Objects.requireNonNull(predecessorResultKind, "predecessorResultKind must not be null");
			Objects.requireNonNull(predecessorEvidenceFingerprint, "predecessorEvidenceFingerprint must not be null");
			if (transitionSequence <= 0)
				throw new IllegalArgumentException("transitionSequence must be positive");
			if (predecessorOperationKind.isPresent() != predecessorResultKind.isPresent() ||
				predecessorOperationKind.isPresent() != predecessorEvidenceFingerprint.isPresent())
			{
				throw new IllegalArgumentException("Predecessor identity must be all present or all absent");
			}
			requireFingerprint(contractFingerprint, "contractFingerprint");
			requireFingerprint(environmentFingerprint, "environmentFingerprint");
			predecessorEvidenceFingerprint.ifPresent(value -> requireFingerprint(value, "predecessorEvidenceFingerprint"));
			requireFingerprint(inputFingerprint, "inputFingerprint");
		}
	}

	/**
	 * Validates one lowercase SHA-256 fixture fingerprint.
	 *
	 * @param value the fingerprint value
	 * @param name the semantic field name
	 */
	private static void requireFingerprint(String value, String name)
	{
		if (value == null || !value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
	}

	/**
	 * Executes the final formal pass through the production formal-trial boundary with typed static harness evidence.
	 *
	 * @param json the fixture JSON codec
	 * @param root the test-owned fixture root
	 * @param evidence the immutable evaluator evidence directory
	 * @param allowances the lineage allowance manifest
	 * @param trials the formal trial manifest
	 * @param policy the loaded statistical policy
	 * @param trial the first sequentially certifying trial number
	 * @return the retained formal-trial result
	 * @throws IOException if retained fixture evidence cannot be created or verified
	 */
	private static FormalTrialOperation.Result executeFinalFormalPass(JsonMapper json, Path root, Path evidence,
		Path allowances, Path trials, FamilySequentialPolicyDocument.Loaded policy, int trial) throws IOException
	{
		List<String> timingValues = new ArrayList<>(List.of("soon", "later"));
		timingValues.add(new SplittableRandom(trial).nextInt(3), "now");
		PromptTestGenerationPolicy generator = new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.FORMAL,
			"family-1", "distribution-1", "uniform-v1", "generator-1", "formal-1", "Reply {{timing}}",
			Map.of("timing", timingValues), Set.of(), 1_000);
		PromptTestGenerationPolicy.PromptTest promptTest = generator.generate(trial);
		requireThat(promptTest.renderedPrompt(), "scheduledPrompt").isEqualTo("Reply now");
		FormalPromptTestScheduler.ScheduledPromptTest scheduled = new FormalPromptTestScheduler.ScheduledPromptTest(
			promptTest, PromptTestPoolPartition.createIdentity(promptTest, "formal-run-" + trial));
		Path project = Files.createDirectory(root.resolve("project"));
		Files.writeString(project.resolve("AGENTS.md"), "Always answer with OK.\n");
		Path artifact = Files.createDirectories(root.resolve("artifact/.codex-plugin"));
		Files.writeString(artifact.resolve("plugin.json"), "{}");
		Path assertions = Files.writeString(evidence.resolve("assertions.json"),
			"{\"assertion-1\":\"response equals OK\"}");
		Path transcript = root.resolve("transcript.jsonl");
		HarnessEvaluationRequest request = new HarnessEvaluationRequest(root.resolve("codex-runner"),
			artifact.getParent(), project, new HarnessModelProfile("gpt-5.6-luna", ReasoningEffort.MEDIUM),
			PromptInput.forPrompt(json, promptTest.renderedPrompt()), transcript, root.resolve("runner-receipt.json"),
			false, List.of(evidence));
		FormalTrialOperation.Request formalRequest = new FormalTrialOperation.Request("formal-operation-" + trial,
			scheduled, request);
		AtomicInteger runnerInvocations = new AtomicInteger();
		AtomicInteger evaluatorInvocations = new AtomicInteger();
		FormalTrialOperation.Result result = FormalTrialOperation.execute(json, CLOCK, allowances, trials, policy,
			formalRequest, ignored ->
		{
			runnerInvocations.incrementAndGet();
			Files.writeString(transcript, "assistant: OK\n");
			Path rawResult = Files.writeString(root.resolve("raw-result.txt"), "OK");
			return new HarnessEvaluationExecution(HarnessEvaluationResult.retained(project, rawResult), 10, 20, false,
				"codex-test", "cat-test", ProviderUsage.of(10, 0, 1), ReproductionState.AVAILABLE,
				Map.of("retained_project", Fingerprints.ofArtifact(project), "raw_result",
					Fingerprints.ofFile(rawResult), "transcript", Fingerprints.ofFile(transcript)));
		}, execution ->
		{
			evaluatorInvocations.incrementAndGet();
			String rawResponse = Files.readString(execution.result().rawResult());
			if (!rawResponse.equals("OK"))
				throw new IOException("Fixture evaluator received an unexpected response");
			return new FormalTrialOperation.Evaluation(FormalTrialLedger.Verdict.PASS, Fingerprints.ofFile(assertions));
		});
		requireThat(runnerInvocations.get(), "runnerInvocations").isEqualTo(1);
		requireThat(evaluatorInvocations.get(), "evaluatorInvocations").isEqualTo(1);
		FormalTrialOperation.Result retry = FormalTrialOperation.execute(json, CLOCK, allowances, trials, policy,
			formalRequest, ignored ->
			{
				throw new AssertionError("Retained formal trial invoked the runner again");
			}, ignored ->
			{
				throw new AssertionError("Retained formal trial invoked the evaluator again");
			});
		requireThat(retry.retainedOutcome(), "retainedOutcome").isEqualTo(result.retainedOutcome());
		return result;
	}

	/**
	 * Appends one distinct passing formal outcome for the only fixture prompt-test family.
	 *
	 * @param json the fixture JSON codec
	 * @param trials the formal trial ledger
	 * @param evidenceDirectory the fixture directory that retains distinct executor and evaluator evidence
	 * @param trial the positive distinct trial number
	 * @throws IOException if the formal evidence cannot be retained
	 */
	private static void recordPass(JsonMapper json, Path trials, Path evidenceDirectory, int trial) throws IOException
	{
		String identifier = Integer.toString(trial);
		Path executorEvidence = Files.writeString(evidenceDirectory.resolve("executor-" + identifier + ".json"),
			"executor=" + identifier + "\n");
		Path evaluatorEvidence = Files.writeString(evidenceDirectory.resolve("evaluator-" + identifier + ".json"),
			"evaluator=" + identifier + "\n");
		FormalTrialLedger.Outcome outcome = new FormalTrialLedger.Outcome("formal-operation-" + identifier,
			"family-1", "prompt-test-" + identifier, FormalTrialLedger.Verdict.PASS,
			Fingerprints.ofFile(executorEvidence), Optional.of(Fingerprints.ofFile(evaluatorEvidence)),
			CLOCK.instant());
		FormalTrialLedger.record(json, trials, outcome);
	}

	/**
	 * Returns the first all-pass sequence length whose production lower-bound formula crosses the certification target.
	 *
	 * @param allowance the candidate's production false-certification allowance
	 * @return the first sequential all-pass count above the target
	 */
	private static int firstSequentialPassingTrial(double allowance)
	{
		for (int trial = 1; trial <= 1_000; ++trial)
		{
			double lowerBound = Statistics.sequentialLowerConfidenceBound(trial, trial, allowance);
			if (lowerBound > 0.95)
				return trial;
		}
		throw new AssertionError("All-pass evidence cannot cross the certification target within 1,000 trials");
	}

	/**
	 * Creates the strict statistical policy used by the vertical fixture.
	 *
	 * @return the policy JSON
	 */
	private static String statisticalPolicyJson()
	{
		return """
			{
			  "schema_version":"family-sequential-policy-v1",
			  "identifier":"family-sequential-v1",
			  "statistical_revision":"uniform-mixture-anytime-v1",
			  "target":"0.95",
			  "maximum_trials_per_family":1000,
			  "lineage_error_budget":"0.05",
			  "candidate_allowance_schedule":"halving-v1"
			}
			""";
	}

	/**
	 * Creates the finite formal policy used by the complete typed fixture.
	 *
	 * @return the frozen executable policy
	 */
	private static PromptTestGenerationPolicy verticalPolicy()
	{
		return new PromptTestGenerationPolicy(PromptTestPoolPartition.Pool.FORMAL, "family-1", "distribution-1",
			"uniform-v1", "generator-1", "formal-1", "Reply {{timing}}",
			Map.of("timing", List.of("soon", "later", "now")), Set.of(), 1_000);
	}

	/**
	 * Creates the frozen contract bound to the vertical fixture's executable policy.
	 *
	 * @param policy the executable policy
	 * @return the frozen contract
	 */
	private static CertificationContract verticalContract(PromptTestGenerationPolicy policy)
	{
		CertificationContract.Artifact artifact = new CertificationContract.Artifact("artifact-1", "AGENTS.md", CONTRACT);
		CertificationContract.SourceSpan span = new CertificationContract.SourceSpan("span-1", "artifact-1", 0, 10,
			"Always answer.");
		CertificationContract.ApplicabilityRule rule = new CertificationContract.ApplicabilityRule("always", "true",
			Set.of("executor-output"), Set.of(CertificationContract.ApplicabilityOutcome.APPLICABLE,
			CertificationContract.ApplicabilityOutcome.NOT_APPLICABLE,
			CertificationContract.ApplicabilityOutcome.INVALID_EVIDENCE));
		CertificationContract.Requirement requirement = new CertificationContract.Requirement("requirement-1",
			"Answer the request.", List.of("span-1"), List.of("always"), List.of());
		CertificationContract.Distribution distribution = new CertificationContract.Distribution("distribution-1",
			"uniform-v1", List.of("timing"), List.of(), "formal-1", policy.supportValidationReceipt());
		CertificationContract.Assertion assertion = new CertificationContract.Assertion("assertion-1", "requirement-1",
			CertificationContract.AssertionClassification.SEMANTIC, "The answer is OK.", Set.of("executor-output"),
			"evaluator-1", CertificationContract.InfrastructureFailureRule.EXCLUDE_FROM_FORMAL_OUTCOMES);
		CertificationContract.LeafFamily family = new CertificationContract.LeafFamily("family-1", null,
			List.of("requirement-1"), List.of("always"), "Answer family.", "distribution-1", "generator-1",
			List.of("assertion-1"));
		CertificationContract.Draft draft = new CertificationContract.Draft("certification-contract-v1", List.of(artifact),
			List.of(span), List.of(requirement), List.of(rule), List.of(distribution), List.of(family), List.of(assertion),
			List.of("evaluator-1"), List.of());
		return CertificationContract.freeze(draft).contract().orElseThrow();
	}

	/**
	 * Creates a lowercase SHA-256 fixture value from one distinct semantic label.
	 *
	 * @param value the value whose fingerprint the fixture needs
	 * @return the lowercase SHA-256 fingerprint
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String fingerprint(String value) throws IOException
	{
		return Fingerprints.ofUtf8(value);
	}

	/**
	 * Verifies that a checked operation fails with I/O.
	 *
	 * @param operation the checked operation
	 * @throws IOException if the expected failure is not thrown
	 */
	private static void requireIo(IoOperation operation) throws IOException
	{
		try
		{
			operation.run();
		}
		catch (IOException _)
		{
			return;
		}
		throw new IOException("Expected IOException");
	}

	/**
	 * Declares one operation that may fail with I/O.
	 */
	@FunctionalInterface
	private interface IoOperation
	{
		/**
		 * Runs the operation.
		 *
		 * @throws IOException if the operation fails
		 */
		void run() throws IOException;
	}
}
