package io.github.cowwoc.cat.common.optimization;

import java.util.Objects;

/** Names every operation in the local optimization workflow. */
public enum OperationKind
{
	DISCOVER_PROMPT_SYSTEM("discover-prompt-system"),
	ANALYZE_PROMPT_SYSTEM("analyze-prompt-system"),
	EXTRACT_CONTRACT("extract-contract"),
	VALIDATE_EVALUATOR_PROTOCOLS("validate-evaluator-protocols"),
	VALIDATE_DETERMINISTIC_CONTROLS("validate-deterministic-controls"),
	FORECAST_COST("forecast-cost"),
	START_CERTIFICATION_PROCESS("start-certification-process"),
	GENERATE_CANDIDATES("generate-candidates"),
	COMPARE_DEVELOPMENT_CANDIDATES("compare-development-candidates"),
	DECIDE_READINESS("decide-readiness"),
	REPORT_OPTIMIZATION_ROUND("report-optimization-round"),
	REPAIR_PROMPT_SYSTEM("repair-prompt-system"),
	VALIDATE_CANDIDATE_CONTRACT("validate-candidate-contract"),
	FREEZE_CANDIDATE_BATCH("freeze-candidate-batch"),
	ASSIGN_ALLOWANCE("assign-allowance"),
	SCHEDULE_TRIAL("schedule-trial"),
	RECORD_OUTCOME("record-outcome"),
	DECIDE("decide"),
	REPORT("report"),
	RETRY_OPERATION("retry-operation"),
	RECOVER_CERTIFICATION_PROCESS("recover-certification-process"),
	FINISH_PREPARATION("finish-preparation"),
	FINISH_CERTIFICATION_PROCESS("finish-certification-process");
	private final String externalValue;

	/**
	 * Creates one serialized operation kind.
	 *
	 * @param externalValue the stable serialized value
	 */
	OperationKind(String externalValue)
	{
		this.externalValue = externalValue;
	}

	/**
	 * Returns the stable serialized value.
	 *
	 * @return the serialized operation kind
	 */
	public String externalValue()
	{
		return externalValue;
	}

	/**
	 * Parses one stable operation kind.
	 *
	 * @param value the serialized operation kind
	 * @return the matching operation kind
	 */
	public static OperationKind fromExternalValue(String value)
	{
		Objects.requireNonNull(value, "value must not be null");
		for (OperationKind candidate : values()) if (candidate.externalValue.equals(value)) return candidate;
		throw new IllegalArgumentException("Unsupported optimization operation: " + value);
	}
}
