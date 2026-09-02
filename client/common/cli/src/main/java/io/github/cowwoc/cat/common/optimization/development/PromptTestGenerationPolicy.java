package io.github.cowwoc.cat.common.optimization.development;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defines and exhaustively validates a finite within-family distribution over superficial prompt variations.
 */
public final class PromptTestGenerationPolicy
{
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_-]*)}}");
	private static final String PARTITION_REVISION = "prompt_test-pool-partition-v1";
	private final PromptTestPoolPartition.Pool pool;
	private final String familyIdentifier;
	private final String distributionIdentifier;
	private final String samplerIdentifier;
	private final String generatorIdentifier;
	private final String seedNamespace;
	private final String promptTemplate;
	private final Map<String, List<String>> dimensions;
	private final Set<ExcludedCombination> exclusions;
	private final long maximumSupportSize;
	private final Support support;
	private final String partitionSourceFingerprint;
	private final String supportValidationReceipt;

	/**
	 * Creates one finite superficial-variation policy.
	 *
	 * @param pool the evidence pool that may consume this distribution
	 * @param familyIdentifier the leaf family that owns every generated prompt_test
	 * @param distributionIdentifier the frozen within-family distribution
	 * @param samplerIdentifier the versioned sampling algorithm
	 * @param generatorIdentifier the versioned generator identifier
	 * @param seedNamespace the frozen independent seed namespace
	 * @param promptTemplate the prompt template containing declared {@code {{dimension}}} placeholders
	 * @param dimensions the finite permitted values for every superficial dimension
	 * @param exclusions invalid complete or partial combinations
	 * @param maximumSupportSize the largest Cartesian support the validator may enumerate
	 */
	public PromptTestGenerationPolicy(PromptTestPoolPartition.Pool pool, String familyIdentifier,
		String distributionIdentifier, String samplerIdentifier, String generatorIdentifier, String seedNamespace,
		String promptTemplate, Map<String, List<String>> dimensions, Set<ExcludedCombination> exclusions,
		long maximumSupportSize)
	{
		this.pool = Objects.requireNonNull(pool, "pool must not be null");
		this.familyIdentifier = requireText(familyIdentifier, "familyIdentifier");
		this.distributionIdentifier = requireText(distributionIdentifier, "distributionIdentifier");
		this.samplerIdentifier = requireText(samplerIdentifier, "samplerIdentifier");
		this.generatorIdentifier = requireText(generatorIdentifier, "generatorIdentifier");
		this.seedNamespace = requireText(seedNamespace, "seedNamespace");
		this.promptTemplate = requireText(promptTemplate, "promptTemplate");
		this.dimensions = copyDimensions(dimensions);
		this.exclusions = copyExclusions(exclusions);
		if (maximumSupportSize < 1 || maximumSupportSize > Integer.MAX_VALUE)
		{
			throw new IllegalArgumentException("maximumSupportSize must be between one and " + Integer.MAX_VALUE +
				": " + maximumSupportSize);
		}
		this.maximumSupportSize = maximumSupportSize;
		validatePlaceholders();
		validateExclusions();
		this.support = partition(enumerateSupport());
		this.partitionSourceFingerprint = computePartitionSourceFingerprint();
		this.supportValidationReceipt = computeSupportValidationReceipt();
	}

	/**
	 * Returns the evidence pool that may consume this distribution.
	 *
	 * @return the evidence pool
	 */
	public PromptTestPoolPartition.Pool pool()
	{
		return pool;
	}

	/**
	 * Returns the owning leaf-family identifier.
	 *
	 * @return the family identifier
	 */
	public String familyIdentifier()
	{
		return familyIdentifier;
	}

	/**
	 * Returns the frozen distribution identifier.
	 *
	 * @return the distribution identifier
	 */
	public String distributionIdentifier()
	{
		return distributionIdentifier;
	}

	/**
	 * Returns the versioned sampler identifier.
	 *
	 * @return the sampler identifier
	 */
	public String samplerIdentifier()
	{
		return samplerIdentifier;
	}

	/**
	 * Returns the versioned generator identifier.
	 *
	 * @return the generator identifier
	 */
	public String generatorIdentifier()
	{
		return generatorIdentifier;
	}

	/**
	 * Returns the independent seed namespace.
	 *
	 * @return the seed namespace
	 */
	public String seedNamespace()
	{
		return seedNamespace;
	}

	/**
	 * Returns the declared superficial variation dimensions in canonical order.
	 *
	 * @return the dimension names
	 */
	public List<String> dimensionNames()
	{
		return List.copyOf(dimensions.keySet());
	}

	/**
	 * Returns the deterministic receipt for the complete validated finite support.
	 *
	 * @return the lowercase SHA-256 support receipt
	 */
	public String supportValidationReceipt()
	{
		return supportValidationReceipt;
	}

	/**
	 * Returns the fingerprint shared by all pool views of the same base generator support.
	 *
	 * @return the lowercase SHA-256 partition-source fingerprint
	 */
	public String partitionSourceFingerprint()
	{
		return partitionSourceFingerprint;
	}

	/**
	 * Returns whether the complete finite support is safe to sample.
	 *
	 * @return the exhaustive support-validation result
	 */
	public Validation validateSupport()
	{
		if (support.reason().isPresent())
			return new Validation(ValidationStatus.BLOCKED, 0, support.reason());
		return new Validation(ValidationStatus.SUPPORTED, support.combinations().size(), Optional.empty());
	}

	/**
	 * Draws one reproducible prompt_test uniformly from the exhaustively validated permitted combinations.
	 *
	 * @param seed the immutable draw seed
	 * @return the generated supported prompt_test
	 */
	public PromptTest generate(long seed)
	{
		Validation validation = validateSupport();
		if (validation.status() == ValidationStatus.BLOCKED)
			throw new IllegalStateException("PromptTest support is blocked: " + validation.reason().orElseThrow());
		List<Map<String, String>> combinations = support.combinations();
		Map<String, String> selected = combinations.get(new SplittableRandom(seed).nextInt(combinations.size()));
		String renderedPrompt = render(selected);
		String fingerprint = sha256(renderedPrompt);
		String instanceIdentifier = seedNamespace + ":" + pool.name() + ":" + generatorIdentifier + ":" +
			Long.toUnsignedString(seed) + ":" + fingerprint;
		return new PromptTest(pool, familyIdentifier, distributionIdentifier, samplerIdentifier, generatorIdentifier,
			seedNamespace, instanceIdentifier, seed, selected, renderedPrompt, fingerprint);
	}

	/**
	 * Checks whether a prompt_test is an exact supported output of this policy.
	 *
	 * @param promptTest the promptTest to check
	 * @return whether every identity, variation, rendered byte, and fingerprint is valid
	 */
	public boolean contains(PromptTest promptTest)
	{
		if (promptTest == null || promptTest.pool() != pool || !promptTest.familyIdentifier().equals(familyIdentifier) ||
			!promptTest.distributionIdentifier().equals(distributionIdentifier) ||
			!promptTest.samplerIdentifier().equals(samplerIdentifier) ||
			!promptTest.generatorIdentifier().equals(generatorIdentifier) ||
			!promptTest.seedNamespace().equals(seedNamespace) ||
			!support.combinations().contains(promptTest.selectedValues()))
		{
			return false;
		}
		String rendered = render(promptTest.selectedValues());
		String fingerprint = sha256(rendered);
		String expectedIdentifier = seedNamespace + ":" + pool.name() + ":" + generatorIdentifier + ":" +
			Long.toUnsignedString(promptTest.seed()) + ":" + fingerprint;
		return promptTest.renderedPrompt().equals(rendered) && promptTest.contentFingerprint().equals(fingerprint) &&
			promptTest.instanceIdentifier().equals(expectedIdentifier);
	}

	/**
	 * Validates that template placeholders and declared dimensions are identical.
	 */
	private void validatePlaceholders()
	{
		Set<String> placeholders = new TreeSet<>();
		Matcher matcher = PLACEHOLDER.matcher(promptTemplate);
		while (matcher.find())
			placeholders.add(matcher.group(1));
		for (String placeholder : placeholders)
		{
			if (!dimensions.containsKey(placeholder))
				throw new IllegalArgumentException("Template references undeclared dimension: " + placeholder);
		}
		for (String dimension : dimensions.keySet())
		{
			if (!placeholders.contains(dimension))
				throw new IllegalArgumentException("Declared dimension is absent from the template: " + dimension);
		}
	}

	/**
	 * Validates every exclusion against the declared finite dimension values.
	 */
	private void validateExclusions()
	{
		for (ExcludedCombination exclusion : exclusions)
		{
			for (Map.Entry<String, String> entry : exclusion.values().entrySet())
			{
				List<String> permitted = dimensions.get(entry.getKey());
				if (permitted == null)
					throw new IllegalArgumentException("Exclusion references undeclared dimension: " + entry.getKey());
				if (!permitted.contains(entry.getValue()))
				{
					throw new IllegalArgumentException("Exclusion references an unsupported value for " + entry.getKey() +
						": " + entry.getValue());
				}
			}
		}
	}

	/**
	 * Enumerates every permitted combination or returns a named deterministic block.
	 *
	 * @return the bounded support
	 */
	private Support enumerateSupport()
	{
		long size = 1;
		for (List<String> values : dimensions.values())
		{
			if (size > maximumSupportSize / values.size())
				return new Support(List.of(), Optional.of(BlockReason.SUPPORT_TOO_LARGE));
			size *= values.size();
		}
		List<Map<String, String>> result = new ArrayList<>((int) size);
		enumerate(new ArrayList<>(dimensions.keySet()), 0, new LinkedHashMap<>(), result);
		if (result.isEmpty())
			return new Support(List.of(), Optional.of(BlockReason.EMPTY_SUPPORT));
		return new Support(result, Optional.empty());
	}

	/**
	 * Selects this policy's deterministic, disjoint portion of the complete base support.
	 *
	 * @param baseSupport the complete permitted support
	 * @return this evidence pool's support or a named deterministic block
	 */
	private Support partition(Support baseSupport)
	{
		if (baseSupport.reason().isPresent())
			return baseSupport;
		List<Map<String, String>> combinations = new ArrayList<>(baseSupport.combinations());
		Map<String, Map<String, String>> byContent = new TreeMap<>();
		for (Map<String, String> combination : combinations)
		{
			String fingerprint = sha256(render(combination));
			if (byContent.putIfAbsent(fingerprint, combination) != null)
				return new Support(List.of(), Optional.of(BlockReason.DUPLICATE_RENDERED_CONTENT));
		}
		if (combinations.size() < PromptTestPoolPartition.Pool.values().length)
			return new Support(List.of(), Optional.of(BlockReason.INSUFFICIENT_PARTITION_SUPPORT));
		combinations.sort(Comparator.comparing(combination -> sha256(render(combination))));

		int developmentCount = Math.max(1, combinations.size() * 3 / 5);
		int readinessCount = Math.max(1, combinations.size() / 5);
		if (developmentCount + readinessCount >= combinations.size())
			developmentCount = combinations.size() - readinessCount - 1;
		List<Map<String, String>> selected = switch (pool)
		{
			case DEVELOPMENT -> combinations.subList(0, developmentCount);
			case READINESS -> combinations.subList(developmentCount, developmentCount + readinessCount);
			case FORMAL -> combinations.subList(developmentCount + readinessCount, combinations.size());
		};
		return new Support(selected, Optional.empty());
	}

	/**
	 * Recursively enumerates the finite Cartesian product.
	 *
	 * @param names the canonical dimension names
	 * @param index the active dimension
	 * @param selected the current partial combination
	 * @param result the permitted combinations
	 */
	private void enumerate(List<String> names, int index, Map<String, String> selected,
		List<Map<String, String>> result)
	{
		if (index == names.size())
		{
			Map<String, String> combination = Collections.unmodifiableMap(new TreeMap<>(selected));
			boolean excluded = exclusions.stream().anyMatch(value -> value.matches(combination));
			if (!excluded)
				result.add(combination);
			return;
		}
		String name = names.get(index);
		for (String value : dimensions.get(name))
		{
			selected.put(name, value);
			enumerate(names, index + 1, selected, result);
		}
		selected.remove(name);
	}

	/**
	 * Renders one supported combination into the frozen prompt template.
	 *
	 * @param selected the selected dimension values
	 * @return the rendered prompt
	 */
	private String render(Map<String, String> selected)
	{
		String result = promptTemplate;
		for (Map.Entry<String, String> entry : selected.entrySet())
			result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
		return result;
	}

	/**
	 * Computes a receipt over the exact executable support and every identity that controls sampling.
	 *
	 * @return the lowercase SHA-256 receipt
	 */
	private String computeSupportValidationReceipt()
	{
		StringBuilder canonical = new StringBuilder();
		append(canonical, partitionSourceFingerprint);
		append(canonical, pool.name());
		append(canonical, distributionIdentifier);
		append(canonical, seedNamespace);
		append(canonical, support.reason().map(Enum::name).orElse("supported"));
		for (Map<String, String> combination : support.combinations())
		{
			for (Map.Entry<String, String> entry : combination.entrySet())
			{
				append(canonical, entry.getKey());
				append(canonical, entry.getValue());
			}
		}
		return sha256(canonical.toString());
	}

	/**
	 * Computes the identity shared by the three evidence-pool views before partitioning.
	 *
	 * @return the lowercase SHA-256 source fingerprint
	 */
	private String computePartitionSourceFingerprint()
	{
		StringBuilder canonical = new StringBuilder();
		appendPartitionSource(canonical);
		return sha256(canonical.toString());
	}

	/**
	 * Appends every value shared by the three pool views of one base support.
	 *
	 * @param canonical the representation under construction
	 */
	private void appendPartitionSource(StringBuilder canonical)
	{
		append(canonical, PARTITION_REVISION);
		append(canonical, familyIdentifier);
		append(canonical, samplerIdentifier);
		append(canonical, generatorIdentifier);
		append(canonical, promptTemplate);
		append(canonical, Long.toString(maximumSupportSize));
		for (Map.Entry<String, List<String>> dimension : dimensions.entrySet())
		{
			append(canonical, dimension.getKey());
			for (String value : dimension.getValue())
				append(canonical, value);
		}
		List<ExcludedCombination> sortedExclusions = new ArrayList<>(exclusions);
		sortedExclusions.sort((first, second) -> first.values().toString().compareTo(second.values().toString()));
		for (ExcludedCombination exclusion : sortedExclusions)
		{
			for (Map.Entry<String, String> entry : exclusion.values().entrySet())
			{
				append(canonical, entry.getKey());
				append(canonical, entry.getValue());
			}
		}
	}

	/**
	 * Appends one unambiguous UTF-16 string field to a canonical in-memory representation.
	 *
	 * @param destination the representation under construction
	 * @param value the field value
	 */
	private static void append(StringBuilder destination, String value)
	{
		destination.append(value.length()).append(':').append(value);
	}

	/**
	 * Copies dimensions into canonical key and value order.
	 *
	 * @param values the supplied dimensions
	 * @return the immutable dimensions
	 */
	private static Map<String, List<String>> copyDimensions(Map<String, List<String>> values)
	{
		Objects.requireNonNull(values, "dimensions must not be null");
		if (values.isEmpty())
			throw new IllegalArgumentException("dimensions must not be empty");
		Map<String, List<String>> result = new TreeMap<>();
		for (Map.Entry<String, List<String>> entry : values.entrySet())
		{
			String name = requireText(entry.getKey(), "dimension name");
			Objects.requireNonNull(entry.getValue(), "dimension values must not be null");
			Set<String> unique = new TreeSet<>();
			for (String value : entry.getValue())
				unique.add(requireText(value, "dimension value"));
			if (unique.isEmpty())
				throw new IllegalArgumentException("Dimension values must not be empty: " + name);
			result.put(name, List.copyOf(unique));
		}
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Copies exclusions and rejects null entries.
	 *
	 * @param values the exclusions
	 * @return the immutable exclusions
	 */
	private static Set<ExcludedCombination> copyExclusions(Set<ExcludedCombination> values)
	{
		Objects.requireNonNull(values, "exclusions must not be null");
		for (ExcludedCombination value : values)
		{
			if (value == null)
				throw new NullPointerException("exclusions must not contain null elements");
		}
		return Set.copyOf(values);
	}

	/**
	 * Validates open text.
	 *
	 * @param value the value
	 * @param name the parameter name
	 * @return the unchanged value
	 */
	private static String requireText(String value, String name)
	{
		if (value == null)
			throw new NullPointerException(name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Calculates one lowercase SHA-256 content fingerprint.
	 *
	 * @param value the content
	 * @return the fingerprint
	 */
	private static String sha256(String value)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Names whether the finite support is usable.
	 */
	public enum ValidationStatus
	{
		SUPPORTED,
		BLOCKED
	}

	/**
	 * Names why a finite support cannot be used.
	 */
	public enum BlockReason
	{
		EMPTY_SUPPORT,
		SUPPORT_TOO_LARGE,
		INSUFFICIENT_PARTITION_SUPPORT,
		DUPLICATE_RENDERED_CONTENT
	}

	/**
	 * Holds one partial or complete combination excluded from the family support.
	 *
	 * @param values the dimension values that identify the excluded subset
	 */
	public record ExcludedCombination(Map<String, String> values)
	{
		/**
		 * Validates and canonicalizes one exclusion.
		 *
		 * @param values the dimension values that identify the excluded subset
		 */
		public ExcludedCombination
		{
			Objects.requireNonNull(values, "values must not be null");
			if (values.isEmpty())
				throw new IllegalArgumentException("Excluded combination must not be empty");
			Map<String, String> copy = new TreeMap<>();
			for (Map.Entry<String, String> entry : values.entrySet())
				copy.put(requireText(entry.getKey(), "exclusion dimension"),
					requireText(entry.getValue(), "exclusion value"));
			values = Collections.unmodifiableMap(copy);
		}

		/**
		 * Returns whether one complete combination belongs to this excluded subset.
		 *
		 * @param candidate the complete combination
		 * @return whether every excluded field matches
		 */
		private boolean matches(Map<String, String> candidate)
		{
			return values.entrySet().stream().allMatch(entry -> entry.getValue().equals(candidate.get(entry.getKey())));
		}
	}

	/**
	 * Holds one exhaustive validation result.
	 *
	 * @param status whether the support is safe to sample
	 * @param validCombinationCount the permitted combination count
	 * @param reason the block reason, when blocked
	 */
	public record Validation(ValidationStatus status, long validCombinationCount, Optional<BlockReason> reason)
	{
		/**
		 * Validates one support result.
		 *
		 * @param status whether the support is safe to sample
		 * @param validCombinationCount the permitted combination count
		 * @param reason the block reason, when blocked
		 */
		public Validation
		{
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(reason, "reason must not be null");
			if (validCombinationCount < 0)
				throw new IllegalArgumentException("validCombinationCount must not be negative");
			if ((status == ValidationStatus.BLOCKED) != reason.isPresent())
				throw new IllegalArgumentException("Only a blocked validation must name a reason");
		}
	}

	/**
	 * Holds one generated supported prompt_test.
	 *
	 * @param pool the evidence pool that owns this prompt_test
	 * @param familyIdentifier the owning leaf family
	 * @param distributionIdentifier the within-family distribution revision
	 * @param samplerIdentifier the sampling algorithm revision
	 * @param generatorIdentifier the generator revision
	 * @param seedNamespace the independent seed namespace
	 * @param instanceIdentifier the draw identity
	 * @param seed the draw seed
	 * @param selectedValues the selected superficial values
	 * @param renderedPrompt the complete rendered prompt
	 * @param contentFingerprint the lowercase SHA-256 rendered-content fingerprint
	 */
	public record PromptTest(PromptTestPoolPartition.Pool pool, String familyIdentifier, String distributionIdentifier,
		String samplerIdentifier, String generatorIdentifier, String seedNamespace, String instanceIdentifier, long seed,
		Map<String, String> selectedValues, String renderedPrompt, String contentFingerprint)
	{
		/**
		 * Validates and snapshots one generated prompt_test.
		 *
		 * @param pool the evidence pool that owns this prompt_test
		 * @param familyIdentifier the owning leaf family
		 * @param distributionIdentifier the within-family distribution revision
		 * @param samplerIdentifier the sampling algorithm revision
		 * @param generatorIdentifier the generator revision
		 * @param seedNamespace the independent seed namespace
		 * @param instanceIdentifier the draw identity
		 * @param seed the draw seed
		 * @param selectedValues the selected superficial values
		 * @param renderedPrompt the complete rendered prompt
		 * @param contentFingerprint the lowercase SHA-256 rendered-content fingerprint
		 */
		public PromptTest
		{
			Objects.requireNonNull(pool, "pool must not be null");
			familyIdentifier = requireText(familyIdentifier, "familyIdentifier");
			distributionIdentifier = requireText(distributionIdentifier, "distributionIdentifier");
			samplerIdentifier = requireText(samplerIdentifier, "samplerIdentifier");
			generatorIdentifier = requireText(generatorIdentifier, "generatorIdentifier");
			seedNamespace = requireText(seedNamespace, "seedNamespace");
			instanceIdentifier = requireText(instanceIdentifier, "instanceIdentifier");
			Objects.requireNonNull(selectedValues, "selectedValues must not be null");
			selectedValues = Collections.unmodifiableMap(new TreeMap<>(selectedValues));
			renderedPrompt = requireText(renderedPrompt, "renderedPrompt");
			contentFingerprint = requireText(contentFingerprint, "contentFingerprint");
			if (!contentFingerprint.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("contentFingerprint must be a lowercase SHA-256 value");
		}
	}

	/**
	 * Holds the exhaustively enumerated support or its block reason.
	 *
	 * @param combinations the permitted combinations
	 * @param reason the reason no support is available
	 */
	private record Support(List<Map<String, String>> combinations, Optional<BlockReason> reason)
	{
		/**
		 * Snapshots one support result.
		 *
		 * @param combinations the permitted combinations
		 * @param reason the reason no support is available
		 */
		private Support
		{
			combinations = List.copyOf(combinations);
			Objects.requireNonNull(reason, "reason must not be null");
		}
	}
}
