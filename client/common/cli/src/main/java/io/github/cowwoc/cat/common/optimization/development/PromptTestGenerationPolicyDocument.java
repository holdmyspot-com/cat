package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.optimization.contract.CertificationContract;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the strict executable prompt_test policies retained beside one certification contract.
 */
public final class PromptTestGenerationPolicyDocument
{
	private static final int MAXIMUM_DOCUMENT_BYTES = 4 * 1024 * 1024;
	private static final String SCHEMA_VERSION = "prompt_test-generation-policy-v1";
	private static final Set<String> ROOT_FIELDS = Set.of("schema_version", "policies");
	private static final Set<String> POLICY_FIELDS = Set.of("pool", "family_id", "distribution_id", "sampler_id",
		"generator_id", "seed_namespace", "prompt_template", "dimensions", "exclusions", "maximum_support_size");

	/**
	 * Reads and validates every evidence-pool policy.
	 *
	 * @param json the JSON codec
	 * @param source the prompt_test-policy document
	 * @return the executable policy catalog
	 * @throws IOException if the document is malformed, unsupported, incomplete, or exceeds its size bound
	 */
	public static Catalog read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(source, "source must not be null");
		String contents = BoundedInput.readUtf8(source, MAXIMUM_DOCUMENT_BYTES, "PromptTest-generation policy");
		try
		{
			JsonMapper strictJson = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
			JsonNode parsed = strictJson.readTree(contents);
			if (!(parsed instanceof ObjectNode document))
				throw new IOException("PromptTest-generation policy is not a JSON object: " + source);
			verifyFields(document, ROOT_FIELDS, "PromptTest-generation policy", source);
			String schemaVersion = requiredText(document, "schema_version", "PromptTest-generation policy", source);
			if (!schemaVersion.equals(SCHEMA_VERSION))
				throw new IOException("Unsupported prompt_test-generation policy schema " + schemaVersion + ": " + source);

			ArrayNode encodedPolicies = requiredArray(document, "policies", "PromptTest-generation policy", source);
			List<PromptTestGenerationPolicy> policies = new ArrayList<>(encodedPolicies.size());
			for (JsonNode encodedPolicy : encodedPolicies)
			{
				if (!(encodedPolicy instanceof ObjectNode policy))
					throw new IOException("PromptTest-generation policy contains a non-object policy: " + source);
				policies.add(readPolicy(policy, source));
			}
			return catalog(policies, source);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid prompt_test-generation policy JSON at " + source + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Reads one executable policy object.
	 *
	 * @param document the policy object
	 * @param source the policy document path
	 * @return the executable policy
	 * @throws IOException if the policy object is malformed
	 */
	private static PromptTestGenerationPolicy readPolicy(ObjectNode document, Path source) throws IOException
	{
		verifyFields(document, POLICY_FIELDS, "PromptTest-generation policy entry", source);
		PromptTestPoolPartition.Pool pool = pool(requiredText(document, "pool", "PromptTest-generation policy entry",
			source));
		String familyIdentifier = requiredText(document, "family_id", "PromptTest-generation policy entry", source);
		String distributionIdentifier =
			requiredText(document, "distribution_id", "PromptTest-generation policy entry", source);
		String samplerIdentifier = requiredText(document, "sampler_id", "PromptTest-generation policy entry", source);
		String generatorIdentifier = requiredText(document, "generator_id", "PromptTest-generation policy entry", source);
		String seedNamespace = requiredText(document, "seed_namespace", "PromptTest-generation policy entry", source);
		String promptTemplate = requiredText(document, "prompt_template", "PromptTest-generation policy entry", source);
		Map<String, List<String>> dimensions = dimensions(
			requiredObject(document, "dimensions", "PromptTest-generation policy entry", source), source);
		Set<PromptTestGenerationPolicy.ExcludedCombination> exclusions = exclusions(
			requiredArray(document, "exclusions", "PromptTest-generation policy entry", source), source);
		long maximumSupportSize = positiveLong(document, "maximum_support_size", source);
		return new PromptTestGenerationPolicy(pool, familyIdentifier, distributionIdentifier, samplerIdentifier,
			generatorIdentifier, seedNamespace, promptTemplate, dimensions, exclusions, maximumSupportSize);
	}

	/**
	 * Creates one complete catalog and verifies cross-pool source identity.
	 *
	 * @param policies the parsed policies
	 * @param source the policy document path
	 * @return the complete catalog
	 * @throws IOException if a family has duplicate, missing, blocked, or inconsistent pool policies
	 */
	private static Catalog catalog(List<PromptTestGenerationPolicy> policies, Path source) throws IOException
	{
		if (policies.isEmpty())
			throw new IOException("PromptTest-generation policy contains no policies: " + source);
		Map<Key, PromptTestGenerationPolicy> byKey = new HashMap<>();
		Map<String, Set<PromptTestPoolPartition.Pool>> poolsByFamily = new HashMap<>();
		Map<String, String> sourceByFamily = new HashMap<>();
		for (PromptTestGenerationPolicy policy : policies)
		{
			Key key = new Key(policy.familyIdentifier(), policy.pool());
			if (byKey.putIfAbsent(key, policy) != null)
				throw new IOException("Duplicate prompt_test policy for " + policy.familyIdentifier() + " / " + policy.pool());
			poolsByFamily.computeIfAbsent(policy.familyIdentifier(), ignored ->
				EnumSet.noneOf(PromptTestPoolPartition.Pool.class)).add(policy.pool());
			String previousSource = sourceByFamily.putIfAbsent(policy.familyIdentifier(),
				policy.partitionSourceFingerprint());
			if (previousSource != null && !previousSource.equals(policy.partitionSourceFingerprint()))
				throw new IOException("PromptTest pool policies have different base support: " + policy.familyIdentifier());
			PromptTestGenerationPolicy.Validation validation = policy.validateSupport();
			if (validation.status() == PromptTestGenerationPolicy.ValidationStatus.BLOCKED)
			{
				throw new IOException("PromptTest policy support is blocked for " + policy.familyIdentifier() + " / " +
					policy.pool() + ": " + validation.reason().orElseThrow());
			}
		}
		Set<PromptTestPoolPartition.Pool> requiredPools = EnumSet.allOf(PromptTestPoolPartition.Pool.class);
		for (Map.Entry<String, Set<PromptTestPoolPartition.Pool>> entry : poolsByFamily.entrySet())
		{
			if (!entry.getValue().equals(requiredPools))
				throw new IOException("PromptTest family does not define every evidence pool: " + entry.getKey());
		}
		List<PromptTestGenerationPolicy> canonical = new ArrayList<>(policies);
		canonical.sort(Comparator.comparing(PromptTestGenerationPolicy::familyIdentifier).
			thenComparing(PromptTestGenerationPolicy::pool));
		return new Catalog(canonical, byKey);
	}

	/**
	 * Reads finite superficial variation sources.
	 *
	 * @param document the dimensions object
	 * @param source the policy document path
	 * @return the dimension values
	 * @throws IOException if a dimension is not an array of nonblank strings
	 */
	private static Map<String, List<String>> dimensions(ObjectNode document, Path source) throws IOException
	{
		Map<String, List<String>> result = new TreeMap<>();
		for (String name : document.propertyNames())
		{
			JsonNode value = document.path(name);
			if (!(value instanceof ArrayNode values))
				throw new IOException("PromptTest dimension is not an array: " + name + " at " + source);
			List<String> dimensionValues = new ArrayList<>(values.size());
			for (JsonNode element : values)
			{
				if (!element.isString() || element.asString().isBlank())
					throw new IOException("PromptTest dimension has an invalid value: " + name + " at " + source);
				dimensionValues.add(element.asString());
			}
			result.put(name, List.copyOf(dimensionValues));
		}
		return Map.copyOf(result);
	}

	/**
	 * Reads complete or partial excluded dimension combinations.
	 *
	 * @param values the exclusion array
	 * @param source the policy document path
	 * @return the exclusions
	 * @throws IOException if an exclusion is malformed or duplicated
	 */
	private static Set<PromptTestGenerationPolicy.ExcludedCombination> exclusions(ArrayNode values, Path source)
		throws IOException
	{
		Set<PromptTestGenerationPolicy.ExcludedCombination> result = new HashSet<>();
		for (JsonNode value : values)
		{
			if (!(value instanceof ObjectNode exclusion))
				throw new IOException("PromptTest exclusion is not an object: " + source);
			Map<String, String> fields = new TreeMap<>();
			for (String name : exclusion.propertyNames())
			{
				JsonNode fieldValue = exclusion.path(name);
				if (!fieldValue.isString() || fieldValue.asString().isBlank())
					throw new IOException("PromptTest exclusion has an invalid value: " + name + " at " + source);
				fields.put(name, fieldValue.asString());
			}
			PromptTestGenerationPolicy.ExcludedCombination parsed =
				new PromptTestGenerationPolicy.ExcludedCombination(fields);
			if (!result.add(parsed))
				throw new IOException("PromptTest-generation policy contains a duplicate exclusion: " + source);
		}
		return Set.copyOf(result);
	}

	/**
	 * Parses one closed prompt_test evidence pool.
	 *
	 * @param value the persisted pool value
	 * @return the evidence pool
	 */
	private static PromptTestPoolPartition.Pool pool(String value)
	{
		return switch (value)
		{
			case "development" -> PromptTestPoolPartition.Pool.DEVELOPMENT;
			case "readiness" -> PromptTestPoolPartition.Pool.READINESS;
			case "formal" -> PromptTestPoolPartition.Pool.FORMAL;
			default -> throw new IllegalArgumentException("Unsupported prompt_test evidence pool: " + value);
		};
	}

	/**
	 * Reads a positive long field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param source the policy document path
	 * @return the positive long
	 * @throws IOException if the value is not a representable positive long
	 */
	private static long positiveLong(ObjectNode document, String field, Path source) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1)
			throw new IOException("PromptTest-generation policy has invalid " + field + ": " + source);
		return value.asLong();
	}

	/**
	 * Reads one required nonblank text field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param description the object's diagnostic description
	 * @param source the policy document path
	 * @return the text
	 * @throws IOException if the value is not nonblank text
	 */
	private static String requiredText(ObjectNode document, String field, String description, Path source)
		throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException(description + " has invalid " + field + ": " + source);
		return value.asString();
	}

	/**
	 * Reads one required object field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param description the object's diagnostic description
	 * @param source the policy document path
	 * @return the object value
	 * @throws IOException if the value is not an object
	 */
	private static ObjectNode requiredObject(ObjectNode document, String field, String description, Path source)
		throws IOException
	{
		JsonNode value = document.path(field);
		if (value instanceof ObjectNode object)
			return object;
		throw new IOException(description + " has invalid " + field + ": " + source);
	}

	/**
	 * Reads one required array field.
	 *
	 * @param document the containing object
	 * @param field the field name
	 * @param description the object's diagnostic description
	 * @param source the policy document path
	 * @return the array value
	 * @throws IOException if the value is not an array
	 */
	private static ArrayNode requiredArray(ObjectNode document, String field, String description, Path source)
		throws IOException
	{
		JsonNode value = document.path(field);
		if (value instanceof ArrayNode array)
			return array;
		throw new IOException(description + " has invalid " + field + ": " + source);
	}

	/**
	 * Verifies one object's complete field set.
	 *
	 * @param object the source object
	 * @param expected the complete field set
	 * @param description the object's diagnostic description
	 * @param source the policy document path
	 * @throws IOException if fields are missing or unsupported
	 */
	private static void verifyFields(ObjectNode object, Set<String> expected, String description, Path source)
		throws IOException
	{
		if (!new HashSet<>(object.propertyNames()).equals(expected))
			throw new IOException(description + " has unsupported or missing fields: " + source);
	}

	/**
	 * Holds one complete executable policy per family and evidence pool.
	 */
	public static final class Catalog
	{
		private final List<PromptTestGenerationPolicy> policies;
		private final Map<Key, PromptTestGenerationPolicy> byKey;

		/**
		 * Creates one validated catalog.
		 *
		 * @param policies every executable policy
		 * @param byKey the same policies indexed by family and pool
		 */
		private Catalog(List<PromptTestGenerationPolicy> policies, Map<Key, PromptTestGenerationPolicy> byKey)
		{
			this.policies = List.copyOf(policies);
			this.byKey = Map.copyOf(byKey);
		}

		/**
		 * Returns every policy in canonical family-and-pool order.
		 *
		 * @return the executable policies
		 */
		public List<PromptTestGenerationPolicy> policies()
		{
			return policies;
		}

		/**
		 * Returns one family's exact evidence-pool policy.
		 *
		 * @param familyIdentifier the frozen family identifier
		 * @param pool the selected evidence pool
		 * @return the executable policy
		 */
		public PromptTestGenerationPolicy policy(String familyIdentifier, PromptTestPoolPartition.Pool pool)
		{
			Objects.requireNonNull(familyIdentifier, "familyIdentifier must not be null");
			Objects.requireNonNull(pool, "pool must not be null");
			PromptTestGenerationPolicy result = byKey.get(new Key(familyIdentifier, pool));
			if (result == null)
				throw new IllegalArgumentException("Unknown prompt_test policy: " + familyIdentifier + " / " + pool);
			return result;
		}

		/**
		 * Binds this document's formal policies to one frozen certification contract.
		 *
		 * @param contract the frozen certification contract
		 * @return the ready formal catalog or every deterministic mismatch
		 */
		public PromptTestContractCatalog.Result bindFormalContract(CertificationContract contract)
		{
			Objects.requireNonNull(contract, "contract must not be null");
			List<PromptTestGenerationPolicy> formalPolicies = policies.stream().
				filter(policy -> policy.pool() == PromptTestPoolPartition.Pool.FORMAL).toList();
			return PromptTestContractCatalog.bind(contract, formalPolicies);
		}
	}

	/**
	 * Identifies one family-and-pool policy.
	 *
	 * @param familyIdentifier the family identifier
	 * @param pool the evidence pool
	 */
	private record Key(String familyIdentifier, PromptTestPoolPartition.Pool pool)
	{
		/**
		 * Validates one policy key.
		 *
		 * @param familyIdentifier the family identifier
		 * @param pool the evidence pool
		 */
		private Key
		{
			Objects.requireNonNull(familyIdentifier, "familyIdentifier must not be null");
			Objects.requireNonNull(pool, "pool must not be null");
		}
	}

	/**
	 * Prevents construction of this static document boundary.
	 */
	private PromptTestGenerationPolicyDocument()
	{
	}
}
