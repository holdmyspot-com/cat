package io.github.cowwoc.cat.common.optimization;

import java.util.Objects;

/** Names every operation result used by the workflow graph. */
public enum OperationResultKind
{
	DISCOVERED("discovered"),
	ANALYZED("analyzed"),
	READY("ready"),
	BLOCKED("blocked"),
	ELIGIBLE("eligible"),
	UNCERTIFIABLE("uncertifiable"),
	PASSED("passed"),
	FORECAST_AVAILABLE("forecast-available"),
	COST_UNAVAILABLE("cost-unavailable"),
	STARTED("started"),
	STALE_PREPARATION("stale-preparation"),
	CHALLENGERS("challengers"),
	NO_ELIGIBLE_CHALLENGER("no-eligible-challenger"),
	PROMOTE("promote"),
	REJECT("reject"),
	MORE_PAIRS("more-pairs"),
	REPAIR_REGRESSION("repair-regression"),
	PLATEAU("plateau"),
	CONTINUE_DEVELOPMENT("continue-development"),
	START_FORMAL_BATCH("start-formal-batch"),
	REPAIR_NAMED_CONFLICT("repair-named-conflict"),
	EDIT_BOUNDARY_EXCEEDED("edit-boundary-exceeded"),
	REPORTED("reported"),
	REPAIRED("repaired"),
	INVALID_CHANGE("invalid-change"),
	PRESERVED("preserved"),
	CONTRACT_CHANGED("contract-changed"),
	BATCH_FROZEN("batch-frozen"),
	RETURN_TO_DEVELOPMENT("return-to-development"),
	STALE_INPUT("stale-input"),
	ASSIGNED("assigned"),
	LINEAGE_ALLOWANCE_EXHAUSTED("lineage-allowance-exhausted"),
	SCHEDULED("scheduled"),
	NO_VALID_SCHEDULE("no-valid-schedule"),
	RECORDED("recorded"),
	INFRASTRUCTURE_FAILURE("infrastructure-failure"),
	INVALID_EVIDENCE("invalid-evidence"),
	SAMPLE("sample"),
	CERTIFIED("certified"),
	FUTILITY("futility"),
	BATCH_NOT_CERTIFIED("batch-not-certified"),
	REPORTED_CERTIFIED("reported-certified"),
	REPORTED_UNSUCCESSFUL_BATCH("reported-unsuccessful-batch"),
	RETRY("retry"),
	RETRY_EXHAUSTED("retry-exhausted"),
	RECOVERED("recovered"),
	UNRECOVERABLE("unrecoverable"),
	NOT_STARTED("not-started"),
	NOT_CERTIFIED("not-certified");
	private final String externalValue;

	/**
	 * Creates one serialized operation result.
	 *
	 * @param externalValue the stable serialized value
	 */
	OperationResultKind(String externalValue)
	{
		this.externalValue = externalValue;
	}

	/**
	 * Returns the stable serialized value.
	 *
	 * @return the serialized result kind
	 */
	public String externalValue()
	{
		return externalValue;
	}

	/**
	 * Parses one stable operation result.
	 *
	 * @param value the serialized result kind
	 * @return the matching result kind
	 */
	public static OperationResultKind fromExternalValue(String value)
	{
		Objects.requireNonNull(value, "value must not be null");
		for (OperationResultKind candidate : values()) if (candidate.externalValue.equals(value)) return candidate;
		throw new IllegalArgumentException("Unsupported optimization result: " + value);
	}
}
