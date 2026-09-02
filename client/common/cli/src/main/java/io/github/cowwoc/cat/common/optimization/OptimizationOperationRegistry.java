package io.github.cowwoc.cat.common.optimization;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Defines every local optimization operation, result, owner, evidence contract, and permitted successor.
 */
public final class OptimizationOperationRegistry
{
	private static final Map<OperationKind, Definition> DEFINITIONS = createDefinitions();

	/**
	 * Prevents construction of this static registry.
	 */
	private OptimizationOperationRegistry()
	{
	}

	/**
	 * Returns every operation definition indexed by its closed identifier.
	 *
	 * @return the immutable operation registry
	 */
	public static Map<OperationKind, Definition> definitions()
	{
		return DEFINITIONS;
	}

	/**
	 * Returns the permitted successors for one operation result.
	 *
	 * @param operationId the completed operation
	 * @param resultId the operation's typed result
	 * @return the immutable successor set
	 */
	public static Set<OperationKind> successors(OperationKind operationId, OperationResultKind resultId)
	{
		Objects.requireNonNull(operationId, "operationId must not be null");
		Objects.requireNonNull(resultId, "resultId must not be null");
		Set<OperationKind> result = DEFINITIONS.get(operationId).transitions().get(resultId);
		if (result == null)
		{
			throw new IllegalArgumentException("Operation " + operationId.externalValue() +
				" does not support result " + resultId.externalValue());
		}
		return result;
	}

	/**
	 * Verifies registry completeness and terminal ownership.
	 */
	public static void validate()
	{
		if (DEFINITIONS.size() != OperationKind.values().length)
			throw new AssertionError("Optimization operation registry is incomplete");
		for (OperationKind operationId : OperationKind.values())
		{
			Definition definition = DEFINITIONS.get(operationId);
			if (definition == null || definition.operationId() != operationId)
				throw new AssertionError("Optimization operation has no matching definition: " + operationId);
			if (definition.transitions().isEmpty())
				throw new AssertionError("Optimization operation has no result transitions: " + operationId);
			for (Set<OperationKind> successors : definition.transitions().values())
			{
				if (successors.isEmpty() && operationId != OperationKind.FINISH_PREPARATION &&
					operationId != OperationKind.FINISH_CERTIFICATION_PROCESS)
				{
					throw new AssertionError("Only a registered finish operation may own a terminal transition: " +
						operationId);
				}
				for (OperationKind successor : successors)
				{
					if (!DEFINITIONS.containsKey(successor))
						throw new AssertionError("Transition names an unregistered successor: " + successor);
				}
			}
		}
	}

	/**
	 * Creates the complete dependency-ordered operation registry.
	 *
	 * @return the immutable definitions
	 */
	private static Map<OperationKind, Definition> createDefinitions()
	{
		Map<OperationKind, Definition> result = new EnumMap<>(OperationKind.class);
		register(result, definition(OperationKind.DISCOVER_PROMPT_SYSTEM, Owner.PROMPT_SYSTEM_SERVICE, false,
			transition(OperationResultKind.DISCOVERED, OperationKind.ANALYZE_PROMPT_SYSTEM),
			transition(OperationResultKind.BLOCKED, OperationKind.FINISH_PREPARATION)));
		register(result, definition(OperationKind.ANALYZE_PROMPT_SYSTEM, Owner.PROMPT_SYSTEM_SERVICE, true,
			transition(OperationResultKind.ANALYZED, OperationKind.EXTRACT_CONTRACT),
			transition(OperationResultKind.BLOCKED, OperationKind.FINISH_PREPARATION)));
		register(result, definition(OperationKind.EXTRACT_CONTRACT, Owner.CONTRACT_ENGINE, true,
			transition(OperationResultKind.READY, OperationKind.VALIDATE_EVALUATOR_PROTOCOLS),
			transition(OperationResultKind.BLOCKED, OperationKind.FINISH_PREPARATION,
				OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.VALIDATE_EVALUATOR_PROTOCOLS, Owner.EVALUATOR_REGISTRY, true,
			transition(OperationResultKind.ELIGIBLE, OperationKind.VALIDATE_DETERMINISTIC_CONTROLS),
			transition(OperationResultKind.UNCERTIFIABLE, OperationKind.FINISH_PREPARATION,
				OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.VALIDATE_DETERMINISTIC_CONTROLS, Owner.VALIDATION_CONTROLLER, false,
			transition(OperationResultKind.PASSED, OperationKind.FORECAST_COST),
			transition(OperationResultKind.BLOCKED, OperationKind.FINISH_PREPARATION,
				OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.FORECAST_COST, Owner.COST_ESTIMATOR, false,
			transition(OperationResultKind.FORECAST_AVAILABLE, OperationKind.START_CERTIFICATION_PROCESS,
				OperationKind.GENERATE_CANDIDATES),
			transition(OperationResultKind.COST_UNAVAILABLE, OperationKind.START_CERTIFICATION_PROCESS,
				OperationKind.GENERATE_CANDIDATES)));
		register(result, definition(OperationKind.START_CERTIFICATION_PROCESS, Owner.CERTIFICATION_PROCESS_SERVICE, false,
			transition(OperationResultKind.STARTED, OperationKind.GENERATE_CANDIDATES),
			transition(OperationResultKind.STALE_PREPARATION, OperationKind.FINISH_PREPARATION)));
		register(result, definition(OperationKind.GENERATE_CANDIDATES, Owner.OPTIMIZER, true,
			transition(OperationResultKind.CHALLENGERS, OperationKind.COMPARE_DEVELOPMENT_CANDIDATES),
			transition(OperationResultKind.NO_ELIGIBLE_CHALLENGER, OperationKind.DECIDE_READINESS)));
		register(result, definition(OperationKind.COMPARE_DEVELOPMENT_CANDIDATES, Owner.DEVELOPMENT_POLICY_ENGINE, true,
			transition(OperationResultKind.PROMOTE, OperationKind.COMPARE_DEVELOPMENT_CANDIDATES,
				OperationKind.GENERATE_CANDIDATES, OperationKind.DECIDE_READINESS),
			transition(OperationResultKind.REJECT, OperationKind.COMPARE_DEVELOPMENT_CANDIDATES,
				OperationKind.GENERATE_CANDIDATES, OperationKind.DECIDE_READINESS),
			transition(OperationResultKind.MORE_PAIRS, OperationKind.COMPARE_DEVELOPMENT_CANDIDATES),
			transition(OperationResultKind.REPAIR_REGRESSION, OperationKind.COMPARE_DEVELOPMENT_CANDIDATES,
				OperationKind.GENERATE_CANDIDATES),
			transition(OperationResultKind.PLATEAU, OperationKind.DECIDE_READINESS)));
		register(result, definition(OperationKind.DECIDE_READINESS, Owner.DEVELOPMENT_POLICY_ENGINE, false,
			transition(OperationResultKind.CONTINUE_DEVELOPMENT, OperationKind.REPORT_OPTIMIZATION_ROUND),
			transition(OperationResultKind.START_FORMAL_BATCH, OperationKind.REPORT_OPTIMIZATION_ROUND),
			transition(OperationResultKind.REPAIR_NAMED_CONFLICT, OperationKind.REPORT_OPTIMIZATION_ROUND),
			transition(OperationResultKind.EDIT_BOUNDARY_EXCEEDED, OperationKind.REPORT_OPTIMIZATION_ROUND),
			transition(OperationResultKind.PLATEAU, OperationKind.REPORT_OPTIMIZATION_ROUND),
			transition(OperationResultKind.INVALID_EVIDENCE, OperationKind.REPORT_OPTIMIZATION_ROUND)));
		register(result, definition(OperationKind.REPORT_OPTIMIZATION_ROUND, Owner.PROGRESS_REPORTER, false,
			transition(OperationResultKind.REPORTED, OperationKind.GENERATE_CANDIDATES, OperationKind.REPAIR_PROMPT_SYSTEM,
				OperationKind.FREEZE_CANDIDATE_BATCH, OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.REPAIR_PROMPT_SYSTEM, Owner.REPAIR_ENGINE, true,
			transition(OperationResultKind.REPAIRED, OperationKind.VALIDATE_CANDIDATE_CONTRACT),
			transition(OperationResultKind.EDIT_BOUNDARY_EXCEEDED, OperationKind.FINISH_CERTIFICATION_PROCESS),
			transition(OperationResultKind.INVALID_CHANGE, OperationKind.GENERATE_CANDIDATES)));
		register(result, definition(OperationKind.VALIDATE_CANDIDATE_CONTRACT, Owner.CONTRACT_ENGINE, false,
			transition(OperationResultKind.PRESERVED, OperationKind.COMPARE_DEVELOPMENT_CANDIDATES),
			transition(OperationResultKind.CONTRACT_CHANGED, OperationKind.GENERATE_CANDIDATES),
			transition(OperationResultKind.INVALID_EVIDENCE, OperationKind.GENERATE_CANDIDATES)));
		register(result, definition(OperationKind.FREEZE_CANDIDATE_BATCH, Owner.BATCH_SERVICE, false,
			transition(OperationResultKind.BATCH_FROZEN, OperationKind.ASSIGN_ALLOWANCE),
			transition(OperationResultKind.RETURN_TO_DEVELOPMENT, OperationKind.GENERATE_CANDIDATES),
			transition(OperationResultKind.STALE_INPUT, OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.ASSIGN_ALLOWANCE, Owner.STATISTICAL_ENGINE, false,
			transition(OperationResultKind.ASSIGNED, OperationKind.SCHEDULE_TRIAL),
			transition(OperationResultKind.LINEAGE_ALLOWANCE_EXHAUSTED, OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.SCHEDULE_TRIAL, Owner.STATISTICAL_ENGINE, true,
			RecoveryBoundary.FINISH_ACTIVE_ATOMIC_OPERATION_THEN_YIELD,
			transition(OperationResultKind.SCHEDULED, OperationKind.RECORD_OUTCOME),
			transition(OperationResultKind.NO_VALID_SCHEDULE, OperationKind.DECIDE)));
		register(result, definition(OperationKind.RECORD_OUTCOME, Owner.STATISTICAL_ENGINE, false,
			transition(OperationResultKind.RECORDED, OperationKind.DECIDE),
			transition(OperationResultKind.INFRASTRUCTURE_FAILURE, OperationKind.RETRY_OPERATION),
			transition(OperationResultKind.INVALID_EVIDENCE, OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.DECIDE, Owner.STATISTICAL_ENGINE, false,
			transition(OperationResultKind.SAMPLE, OperationKind.SCHEDULE_TRIAL),
			transition(OperationResultKind.CERTIFIED, OperationKind.REPORT),
			transition(OperationResultKind.FUTILITY, OperationKind.REPORT),
			transition(OperationResultKind.BATCH_NOT_CERTIFIED, OperationKind.REPORT),
			transition(OperationResultKind.INVALID_EVIDENCE, OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.REPORT, Owner.STATISTICAL_ENGINE, false,
			transition(OperationResultKind.REPORTED_CERTIFIED, OperationKind.FINISH_CERTIFICATION_PROCESS),
			transition(OperationResultKind.REPORTED_UNSUCCESSFUL_BATCH, OperationKind.DECIDE_READINESS)));
		register(result, definition(OperationKind.RETRY_OPERATION, Owner.LIFECYCLE_CONTROLLER, false,
			transition(OperationResultKind.RETRY, OperationKind.SCHEDULE_TRIAL),
			transition(OperationResultKind.RETRY_EXHAUSTED, OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.RECOVER_CERTIFICATION_PROCESS, Owner.CERTIFICATION_PROCESS_SERVICE, false,
			RecoveryBoundary.RECOVER_FROM_TERMINAL_PREDECESSOR,
			transition(OperationResultKind.RECOVERED, OperationKind.GENERATE_CANDIDATES,
				OperationKind.COMPARE_DEVELOPMENT_CANDIDATES, OperationKind.DECIDE_READINESS,
				OperationKind.REPORT_OPTIMIZATION_ROUND, OperationKind.REPAIR_PROMPT_SYSTEM,
				OperationKind.VALIDATE_CANDIDATE_CONTRACT, OperationKind.FREEZE_CANDIDATE_BATCH,
				OperationKind.ASSIGN_ALLOWANCE, OperationKind.SCHEDULE_TRIAL, OperationKind.RECORD_OUTCOME,
				OperationKind.DECIDE, OperationKind.REPORT, OperationKind.RETRY_OPERATION,
				OperationKind.FINISH_CERTIFICATION_PROCESS),
			transition(OperationResultKind.UNRECOVERABLE, OperationKind.FINISH_CERTIFICATION_PROCESS)));
		register(result, definition(OperationKind.FINISH_PREPARATION, Owner.CERTIFICATION_PROCESS_SERVICE, false,
			RecoveryBoundary.TERMINAL_PREPARATION, transition(OperationResultKind.NOT_STARTED)));
		register(result, definition(OperationKind.FINISH_CERTIFICATION_PROCESS, Owner.CERTIFICATION_PROCESS_SERVICE, false,
			RecoveryBoundary.TERMINAL_CERTIFICATION_PROCESS,
			transition(OperationResultKind.CERTIFIED), transition(OperationResultKind.NOT_CERTIFIED)));
		Map<OperationKind, Definition> immutable = Collections.unmodifiableMap(result);
		validateDefinitions(immutable);
		return immutable;
	}

	/**
	 * Adds one uniquely owned operation definition.
	 *
	 * @param definitions the mutable registry
	 * @param definition the definition to add
	 */
	private static void register(Map<OperationKind, Definition> definitions, Definition definition)
	{
		if (definitions.putIfAbsent(definition.operationId(), definition) != null)
			throw new AssertionError("Duplicate optimization operation definition: " + definition.operationId());
	}

	/**
	 * Creates an ordinary verified-predecessor operation definition.
	 *
	 * @param operationId the operation identifier
	 * @param owner the authoritative owner
	 * @param modelConsuming whether the operation may call a model
	 * @param transitions the result transitions
	 * @return the complete definition
	 */
	@SafeVarargs
	private static Definition definition(OperationKind operationId, Owner owner, boolean modelConsuming,
		Map.Entry<OperationResultKind, Set<OperationKind>>... transitions)
	{
		return definition(operationId, owner, modelConsuming, RecoveryBoundary.RETRY_FROM_TERMINAL_PREDECESSOR,
			transitions);
	}

	/**
	 * Creates a definition with an explicit recovery boundary.
	 *
	 * @param operationId the operation identifier
	 * @param owner the authoritative owner
	 * @param modelConsuming whether the operation may call a model
	 * @param recoveryBoundary the interruption and retry boundary
	 * @param transitions the result transitions
	 * @return the complete definition
	 */
	@SafeVarargs
	private static Definition definition(OperationKind operationId, Owner owner, boolean modelConsuming,
		RecoveryBoundary recoveryBoundary, Map.Entry<OperationResultKind, Set<OperationKind>>... transitions)
	{
		Map<OperationResultKind, Set<OperationKind>> transitionMap = new EnumMap<>(OperationResultKind.class);
		for (Map.Entry<OperationResultKind, Set<OperationKind>> transition : transitions)
		{
			if (transitionMap.putIfAbsent(transition.getKey(), transition.getValue()) != null)
				throw new AssertionError("Duplicate operation result: " + transition.getKey());
		}
		ModelCostBehavior costBehavior = ModelCostBehavior.NO_MODEL_COST;
		if (modelConsuming)
			costBehavior = ModelCostBehavior.MAY_CONSUME_MODEL;
		return new Definition(operationId, owner, operationId.externalValue() + "-input-v1",
			operationId.externalValue() + "-evidence-v1", costBehavior,
			IdempotencyBehavior.RETAIN_TERMINAL_RESULT, recoveryBoundary, Collections.unmodifiableMap(transitionMap));
	}

	/**
	 * Creates one result transition.
	 *
	 * @param resultId the typed result
	 * @param successors the permitted successor operations
	 * @return the map entry used by a definition
	 */
	private static Map.Entry<OperationResultKind, Set<OperationKind>> transition(OperationResultKind resultId,
		OperationKind... successors)
	{
		Set<OperationKind> successorSet = EnumSet.noneOf(OperationKind.class);
		Collections.addAll(successorSet, successors);
		return Map.entry(resultId, Collections.unmodifiableSet(successorSet));
	}

	/**
	 * Verifies a newly built map before publishing it as the registry.
	 *
	 * @param definitions the proposed immutable definitions
	 */
	private static void validateDefinitions(Map<OperationKind, Definition> definitions)
	{
		if (definitions.size() != OperationKind.values().length)
			throw new AssertionError("Optimization operation registry is incomplete");
		for (OperationKind operationId : OperationKind.values())
		{
			if (!definitions.containsKey(operationId))
				throw new AssertionError("Optimization operation is unregistered: " + operationId);
		}
	}

	/**
	 * Names the authoritative component that owns an operation.
	 */
	public enum Owner
	{
		PROMPT_SYSTEM_SERVICE,
		CONTRACT_ENGINE,
		EVALUATOR_REGISTRY,
		VALIDATION_CONTROLLER,
		COST_ESTIMATOR,
		OPTIMIZER,
		DEVELOPMENT_POLICY_ENGINE,
		PROGRESS_REPORTER,
		REPAIR_ENGINE,
		BATCH_SERVICE,
		STATISTICAL_ENGINE,
		LIFECYCLE_CONTROLLER,
		CERTIFICATION_PROCESS_SERVICE
	}

	/**
	 * Names whether an operation may call a local model provider.
	 */
	public enum ModelCostBehavior
	{
		NO_MODEL_COST,
		MAY_CONSUME_MODEL
	}

	/**
	 * Names the idempotency contract shared by local operations.
	 */
	public enum IdempotencyBehavior
	{
		RETAIN_TERMINAL_RESULT
	}

	/**
	 * Names the interruption and recovery boundary.
	 */
	public enum RecoveryBoundary
	{
		RETRY_FROM_TERMINAL_PREDECESSOR,
		FINISH_ACTIVE_ATOMIC_OPERATION_THEN_YIELD,
		RECOVER_FROM_TERMINAL_PREDECESSOR,
		TERMINAL_PREPARATION,
		TERMINAL_CERTIFICATION_PROCESS
	}

	/**
	 * Holds one complete executable operation definition.
	 *
	 * @param operationId the closed operation identifier
	 * @param owner the authoritative component
	 * @param inputSchemaId the strict immutable-input schema
	 * @param evidenceSchemaId the strict terminal-evidence schema
	 * @param modelCostBehavior whether the operation may consume model capacity
	 * @param idempotencyBehavior the retry contract
	 * @param recoveryBoundary the interruption and recovery contract
	 * @param transitions each supported result and its permitted successors
	 */
	public record Definition(OperationKind operationId, Owner owner, String inputSchemaId, String evidenceSchemaId,
		ModelCostBehavior modelCostBehavior, IdempotencyBehavior idempotencyBehavior,
		RecoveryBoundary recoveryBoundary, Map<OperationResultKind, Set<OperationKind>> transitions)
	{
		/**
		 * Validates and snapshots one definition.
		 *
		 * @param operationId the closed operation identifier
		 * @param owner the authoritative component
		 * @param inputSchemaId the strict immutable-input schema
		 * @param evidenceSchemaId the strict terminal-evidence schema
		 * @param modelCostBehavior whether the operation may consume model capacity
		 * @param idempotencyBehavior the retry contract
		 * @param recoveryBoundary the interruption and recovery contract
		 * @param transitions each supported result and its permitted successors
		 */
		public Definition
		{
			Objects.requireNonNull(operationId, "operationId must not be null");
			Objects.requireNonNull(owner, "owner must not be null");
			Objects.requireNonNull(inputSchemaId, "inputSchemaId must not be null");
			Objects.requireNonNull(evidenceSchemaId, "evidenceSchemaId must not be null");
			Objects.requireNonNull(modelCostBehavior, "modelCostBehavior must not be null");
			Objects.requireNonNull(idempotencyBehavior, "idempotencyBehavior must not be null");
			Objects.requireNonNull(recoveryBoundary, "recoveryBoundary must not be null");
			Objects.requireNonNull(transitions, "transitions must not be null");
			transitions = Map.copyOf(transitions);
		}
	}
}
