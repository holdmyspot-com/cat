package io.github.cowwoc.cat.common.commercial;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Holds one opt-in Usage analytics event after validating its fields against a versioned local allowlist.
 */
public final class UsageAnalyticsEvent
{
	private static final String INITIAL_POLICY = "usage-analytics-v1";
	private static final Set<String> INITIAL_FIELDS = Set.of("file_count", "line_count", "sentence_count",
		"model_identifier", "harness_identifier", "family_count", "trial_count", "token_count", "rounded_duration",
		"estimated_cost", "outcome", "prediction_error");
	private final String policyVersion;
	private final Map<String, Object> fields;

	/**
	 * Creates one validated event.
	 *
	 * @param policyVersion the allowlist policy version
	 * @param fields the validated event fields
	 */
	private UsageAnalyticsEvent(String policyVersion, Map<String, Object> fields)
	{
		this.policyVersion = policyVersion;
		this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}

	/**
	 * Validates untyped event fields at the local transmission boundary.
	 *
	 * @param policyVersion the exact published allowlist version
	 * @param fields the proposed aggregate fields
	 * @return the validated event
	 */
	public static UsageAnalyticsEvent fromExternalValues(String policyVersion, Map<String, Object> fields)
	{
		if (policyVersion == null)
			throw new NullPointerException("policyVersion must not be null");
		if (!policyVersion.equals(INITIAL_POLICY))
			throw new IllegalArgumentException("Unsupported Usage analytics policy: " + policyVersion);
		if (fields == null)
			throw new NullPointerException("fields must not be null");

		Map<String, Object> sorted = new TreeMap<>();
		for (Map.Entry<String, Object> entry : fields.entrySet())
		{
			String field = entry.getKey();
			if (field == null)
				throw new NullPointerException("Usage analytics field name must not be null");
			if (!INITIAL_FIELDS.contains(field))
				throw new IllegalArgumentException("Usage analytics field is not allowed: " + field);
			Object value = entry.getValue();
			if (!(value instanceof String) && !(value instanceof Number))
				throw new IllegalArgumentException("Usage analytics field must be text or a number: " + field);
			if (value instanceof String text && text.isBlank())
				throw new IllegalArgumentException("Usage analytics text field must not be blank: " + field);
			if (value instanceof Number number && (!Double.isFinite(number.doubleValue()) || number.doubleValue() < 0))
				throw new IllegalArgumentException("Usage analytics numeric field must be finite and nonnegative: " + field);
			sorted.put(field, value);
		}
		return new UsageAnalyticsEvent(policyVersion, sorted);
	}

	/**
	 * Returns whether the user explicitly enabled transmission.
	 *
	 * @param optedIn whether the active account or client setting opted in
	 * @return whether this event may be sent
	 */
	public boolean mayTransmit(boolean optedIn)
	{
		return optedIn;
	}

	/**
	 * Returns the exact allowlist policy version.
	 *
	 * @return the policy version
	 */
	public String policyVersion()
	{
		return policyVersion;
	}

	/**
	 * Returns the validated fields in deterministic order.
	 *
	 * @return the aggregate fields
	 */
	public Map<String, Object> fields()
	{
		return fields;
	}
}
