package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model and reasoning-effort combinations used to measure a contract's pass rates. It records the combinations
 * available from the harness, their capability ranking, the choices selected by prompt files, the user's restriction,
 * the resulting pairs of prompt test and trial profile, called cells, and the certification scope.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param inventory   the profile inventory and its source for the individual or first evaluator
 * @param ranking     the model families ranked by capability for the individual or first evaluator
 * @param bindings    the prompt files that bind a model or an effort for the individual or first evaluator
 * @param restriction the selected profile patterns for the individual or first evaluator, or empty
 * @param described   the certification scope patterns for the individual or first evaluator, or empty
 * @param scope       the certification scope, or null until the user chooses it at confirmation
 * @param cells       every cell, in evaluator order and then test order
 * @param byEvaluator the assigned evaluator profile sets, or empty for an individual profile set
 */
public record TrialProfiles(ProfileCatalog inventory, CapabilityRanking ranking, List<ProfileBinding> bindings,
	List<TrialProfile> restriction, List<TrialProfile> described, CertificationScope scope, List<Cell> cells,
	Map<Harness, TrialProfiles> byEvaluator)
{
	/**
	 * The identifier of a contract cell under a tool-output token limit: the contract cell's identifier, then
	 * {@code /tool-output-} and the token count.
	 */
	private static final Pattern LIMITED_CELL = Pattern.compile("(.+)/" + TrialProfile.TOOL_OUTPUT_LABEL +
		"([1-9][0-9]*)");

	/**
	 * Combines independently computed profile sets into the cells of one contract.
	 *
	 * @param evaluators a nonempty map of harnesses to their individual profile sets
	 * @return the combined profile sets; each cell names its evaluator and shared contracts qualify their addresses
	 * @throws NullPointerException     if the map or one of its keys or values is null
	 * @throws IllegalArgumentException if a profile set is itself combined, a cell is assigned to another evaluator,
	 *   or the profile sets use different certification scopes
	 */
	public static TrialProfiles forEvaluators(Map<Harness, TrialProfiles> evaluators)
	{
		requireThat(evaluators, "evaluators").isNotEmpty();
		Map<Harness, TrialProfiles> assigned = new EnumMap<>(Harness.class);
		List<Cell> cells = new ArrayList<>();
		boolean shared = evaluators.size() > 1;
		for (Map.Entry<Harness, TrialProfiles> entry : new EnumMap<>(Map.copyOf(evaluators)).entrySet())
		{
			Harness evaluator = entry.getKey();
			TrialProfiles local = entry.getValue();
			if (!local.byEvaluator().isEmpty())
				throw new IllegalArgumentException("Expected an individual profile set for " + evaluator.commandName());
			List<Cell> localCells = local.cells().stream().map(cell -> assign(cell, evaluator, shared)).toList();
			assigned.put(evaluator, new TrialProfiles(local.inventory(), local.ranking(), local.bindings(),
				local.restriction(), local.described(), local.scope(), localCells));
			cells.addAll(localCells);
		}
		TrialProfiles first = assigned.values().iterator().next();
		return new TrialProfiles(first.inventory(), first.ranking(), first.bindings(), first.restriction(),
			first.described(), first.scope(), cells, assigned);
	}

	/**
	 * Creates the trial profiles.
	 *
	 * @param inventory   the inventory and its source for the individual or first evaluator
	 * @param ranking     the capability ranking for the individual or first evaluator
	 * @param bindings    the model and effort bindings for the individual or first evaluator
	 * @param restriction the patterns that the user restricted the profile set to, or empty
	 * @param described   the patterns of the profiles that the user described as the certification scope, or empty
	 * @param scope       the certification scope, or null until the user chooses it
	 * @param cells       every cell, in evaluator order and then test order
	 * @param byEvaluator the assigned evaluator profile sets, or empty for an individual profile set
	 * @throws NullPointerException     if any of the arguments other than {@code scope} are null
	 * @throws IllegalArgumentException if {@code cells} is empty, two cells share an identifier, two bindings name the
	 *   same file, a restriction or described pattern binds nothing or matches no inventory profile, the described
	 *   patterns leave a test of several cells without a cell, {@code scope} is
	 *   {@link CertificationScope#DESCRIBED} and no pattern is described, or a scope is chosen while an inventory
	 *   model's family is missing from {@code ranking}
	 */
	public TrialProfiles
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(ranking, "ranking").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		requireThat(restriction, "restriction").isNotNull();
		requireThat(described, "described").isNotNull();
		requireThat(cells, "cells").isNotEmpty();
		bindings = List.copyOf(bindings);
		restriction = List.copyOf(restriction);
		described = List.copyOf(described);
		cells = List.copyOf(cells);
		byEvaluator = Map.copyOf(byEvaluator);
		if (!byEvaluator.isEmpty())
		{
			byEvaluator = Collections.unmodifiableMap(new EnumMap<>(byEvaluator));
			verifyCombined(scope, cells, byEvaluator);
		}
		else
			verifyLocal(inventory, ranking, bindings, restriction, described, scope, cells);
	}

	/**
	 * Creates one evaluator's profile set before assignment.
	 *
	 * @param inventory   the inventory and its source
	 * @param ranking     the model families ranked by capability
	 * @param bindings    the model and effort bindings of prompt files
	 * @param restriction the selection patterns, or empty
	 * @param described   the certification scope patterns, or empty
	 * @param scope       the certification scope, or null until confirmed
	 * @param cells       the individual profile set's cells
	 * @throws NullPointerException     if any required value is null
	 * @throws IllegalArgumentException if the canonical constructor rejects a value
	 */
	public TrialProfiles(ProfileCatalog inventory, CapabilityRanking ranking, List<ProfileBinding> bindings,
		List<TrialProfile> restriction, List<TrialProfile> described, CertificationScope scope, List<Cell> cells)
	{
		this(inventory, ranking, bindings, restriction, described, scope, cells, Map.of());
	}

	/**
	 * Assigns an individual cell and checks the evaluator of a previously assigned cell.
	 *
	 * @param cell      the cell to assign
	 * @param evaluator the evaluator of the cell
	 * @param shared    whether the address includes an evaluator prefix
	 * @return the assigned cell
	 * @throws IllegalArgumentException if a recorded cell belongs to another evaluator or has an invalid prefix
	 */
	private static Cell assign(Cell cell, Harness evaluator, boolean shared)
	{
		String id = cell.id();
		String prefix = evaluator.commandName() + ":";
		if (shared && cell.evaluator().isEmpty())
			id = prefix + id;
		if (cell.evaluator().orElse(evaluator) != evaluator || shared && !id.startsWith(prefix))
			throw new IllegalArgumentException("The cell '" + id + "' is not qualified for " + evaluator.commandName());
		return new Cell(id, cell.testId(), cell.profile(), Optional.of(evaluator));
	}

	/**
	 * Checks that the combined cells and scope agree with the evaluator profile sets.
	 *
	 * @param scope       the shared certification scope
	 * @param cells       the combined cells in evaluator order
	 * @param byEvaluator the evaluator profile sets in evaluator order
	 * @throws IllegalArgumentException if the profile sets do not account for each cell and agree on scope
	 */
	private static void verifyCombined(CertificationScope scope, List<Cell> cells,
		Map<Harness, TrialProfiles> byEvaluator)
	{
		List<Cell> combined = new ArrayList<>();
		Set<String> addresses = new HashSet<>();
		for (Map.Entry<Harness, TrialProfiles> entry : byEvaluator.entrySet())
		{
			Harness evaluator = entry.getKey();
			TrialProfiles local = entry.getValue();
			if (local.scope() != scope || !local.byEvaluator().isEmpty())
				throw new IllegalArgumentException("The profile set for " + evaluator.commandName() +
					" must use the shared certification scope");
			for (Cell cell : local.cells())
			{
				if (cell.evaluator().orElse(null) != evaluator || !addresses.add(cell.id()))
					throw new IllegalArgumentException("The cell '" + cell.id() + "' does not have one evaluator");
				combined.add(cell);
			}
		}
		if (!cells.equals(combined))
			throw new IllegalArgumentException("Combined cells differ from the evaluator profile sets");
	}

	/**
	 * Checks profile selections within an individual evaluator's inventory.
	 *
	 * @param inventory   the inventory and its source
	 * @param ranking     the model families ranked by capability
	 * @param bindings    the model and effort bindings of prompt files
	 * @param restriction the selection patterns, or empty
	 * @param described   the certification scope patterns, or empty
	 * @param scope       the certification scope, or null until confirmed
	 * @param cells       the individual profile set's cells
	 * @throws IllegalArgumentException if the profiles cannot support the contract
	 */
	private static void verifyLocal(ProfileCatalog inventory, CapabilityRanking ranking, List<ProfileBinding> bindings,
		List<TrialProfile> restriction, List<TrialProfile> described, CertificationScope scope, List<Cell> cells)
	{
		Set<String> files = new HashSet<>();
		for (ProfileBinding binding : bindings)
		{
			if (!files.add(binding.file()))
				throw new IllegalArgumentException("The file '" + binding.file() + "' is bound twice");
		}
		for (TrialProfile pattern : restriction)
			requireRestrictable(inventory, pattern);
		Set<String> ids = new HashSet<>();
		for (Cell cell : cells)
		{
			if (!ids.add(cell.id()))
				throw new IllegalArgumentException("The cell '" + cell.id() + "' is listed twice");
		}
		requireDescribable(inventory, described, cells);
		if (scope == CertificationScope.DESCRIBED && described.isEmpty())
		{
			throw new IllegalArgumentException("The described certification scope needs the cells that the user " +
				"described in revision feedback, and none are described");
		}
		List<String> unranked = ranking.unranked(inventory);
		if (scope != null && !unranked.isEmpty())
		{
			throw new IllegalArgumentException("The capability ranking (" + ranking.source() + ") does not rank the " +
				"family of the inventory models " + String.join(", ", unranked) + ". Rank them in revision feedback " +
				"before confirming.");
		}
	}

	/**
	 * Requires the described patterns to select inventory profiles and to leave every test of several cells with a
	 * cell.
	 *
	 * @param inventory the inventory
	 * @param described the described patterns, or empty
	 * @param cells every cell, grouped by test
	 * @throws IllegalArgumentException if a pattern binds nothing or matches no inventory profile, or the patterns
	 *   leave a test of several cells without a cell
	 */
	private static void requireDescribable(ProfileCatalog inventory, List<TrialProfile> described, List<Cell> cells)
	{
		if (described.isEmpty())
			return;
		for (TrialProfile pattern : described)
		{
			if (!pattern.isBound())
				throw new IllegalArgumentException("A described certification cell must name a model, an effort or both");
			if (inventory.isOutside(pattern))
			{
				throw new IllegalArgumentException("The described certification cell " + pattern.label() + " matches " +
					"no profile in the inventory (" + inventory.source() + ")");
			}
		}
		Map<String, List<TrialProfile>> byTest = new LinkedHashMap<>();
		for (Cell cell : cells)
			byTest.computeIfAbsent(cell.testId(), _ -> new ArrayList<>()).add(cell.profile());
		for (Map.Entry<String, List<TrialProfile>> entry : byTest.entrySet())
		{
			List<TrialProfile> profiles = entry.getValue();
			if (profiles.size() > 1 && profiles.stream().noneMatch(profile -> isDescribed(profile, described)))
			{
				throw new IllegalArgumentException("The described certification cells " + labels(described) +
					" leave the test '" + entry.getKey() + "' with no cell to certify. Its cells are " +
					labels(profiles));
			}
		}
	}

	/**
	 * Indicates whether a profile matches a described pattern.
	 *
	 * @param profile a profile
	 * @param described the described patterns
	 * @return {@code true} if a pattern matches the profile
	 */
	private static boolean isDescribed(TrialProfile profile, List<TrialProfile> described)
	{
		return described.stream().anyMatch(profile::matches);
	}

	/**
	 * Indicates whether the user described a cell's profile as part of the certification scope.
	 *
	 * @param cell a cell
	 * @return {@code true} if a described pattern matches the cell's profile
	 * @throws NullPointerException if {@code cell} is null
	 */
	public boolean isDescribed(Cell cell)
	{
		requireThat(cell, "cell").isNotNull();
		if (!byEvaluator.isEmpty())
			return forEvaluator(cell.evaluator().orElseThrow()).isDescribed(cell);
		return isDescribed(cell.profile(), described);
	}

	/**
	 * Requires a restriction pattern to select part of the inventory.
	 *
	 * @param inventory the inventory
	 * @param pattern the pattern
	 * @throws IllegalArgumentException if {@code pattern} binds nothing or matches no inventory profile
	 */
	private static void requireRestrictable(ProfileCatalog inventory, TrialProfile pattern)
	{
		if (!pattern.isBound())
			throw new IllegalArgumentException("A profile restriction must name a model, an effort or both");
		if (inventory.isOutside(pattern))
		{
			throw new IllegalArgumentException("The profile restriction " + pattern.label() + " matches no " +
				"profile in the inventory (" + inventory.source() + ")");
		}
	}

	/**
	 * Describes the trial profiles for the user: the inventory and its source, the capability ranking and its source
	 * with any unranked models, the bindings, marking each value outside the inventory, the restriction, the
	 * described certification cells and each test's cells.
	 *
	 * @return the lines of the description
	 */
	public List<String> describe()
	{
		if (byEvaluator.size() == 1)
			return byEvaluator.values().iterator().next().describe();
		if (!byEvaluator.isEmpty())
		{
			List<String> byHarness = new ArrayList<>();
			for (Map.Entry<Harness, TrialProfiles> entry : byEvaluator.entrySet())
			{
				byHarness.add("Evaluation harness: " + entry.getKey().commandName());
				byHarness.addAll(entry.getValue().describe());
			}
			return byHarness;
		}
		List<String> lines = new ArrayList<>();
		lines.add("Trial profile inventory (" + inventory.source() + "): " + labels(inventory.profiles()));
		lines.add(ranking.describe());
		List<String> unranked = unranked();
		if (!unranked.isEmpty())
		{
			lines.add("Unranked models: " + String.join(", ", unranked) + " (confirmation is refused until revision " +
				"feedback ranks their families)");
		}
		if (bindings.isEmpty())
			lines.add("Profile bindings: none");
		else
		{
			lines.add("Profile bindings:");
			for (ProfileBinding binding : bindings)
			{
				String outside = "";
				if (inventory.isOutside(binding.profile()))
					outside = " (outside the inventory, used as written)";
				lines.add("  - " + binding.file() + " binds " + binding.profile().label() + outside);
			}
		}
		if (!restriction.isEmpty())
			lines.add("Profile restriction: " + labels(restriction));
		if (!described.isEmpty())
			lines.add("Described certification cells: " + labels(described));
		lines.add("Cells:");
		Map<String, List<TrialProfile>> byTest = new LinkedHashMap<>();
		for (Cell cell : cells)
			byTest.computeIfAbsent(cell.testId(), _ -> new ArrayList<>()).add(cell.profile());
		Map<List<TrialProfile>, List<String>> byProfiles = new LinkedHashMap<>();
		for (Map.Entry<String, List<TrialProfile>> entry : byTest.entrySet())
			byProfiles.computeIfAbsent(entry.getValue(), _ -> new ArrayList<>()).add(entry.getKey());
		String prefix = addressPrefix();
		for (Map.Entry<List<TrialProfile>, List<String>> entry : byProfiles.entrySet())
			lines.add("  - " + describeGroup(entry.getValue(), entry.getKey(), prefix));
		return lines;
	}

	/**
	 * Finds the common evaluator prefix for the displayed addresses of an individual profile set.
	 *
	 * @return the evaluator prefix, or empty for unqualified addresses
	 */
	private String addressPrefix()
	{
		Cell first = cells.getFirst();
		if (first.evaluator().isEmpty())
			return "";
		String prefix = first.evaluator().orElseThrow().commandName() + ":";
		if (first.id().startsWith(prefix))
			return prefix;
		return "";
	}

	/**
	 * Describes the prompt tests that share a profile set: how many cells each has, how the cells are named and the
	 * profiles.
	 *
	 * @param testIds  the identifiers of the tests
	 * @param profiles the profiles of each test's cells
	 * @param prefix   the evaluator prefix of the cell addresses, or empty
	 * @return the description
	 */
	private static String describeGroup(List<String> testIds, List<TrialProfile> profiles, String prefix)
	{
		String tests;
		String each;
		if (testIds.size() == 1)
		{
			tests = "test ";
			each = "";
		}
		else
		{
			tests = "tests ";
			each = " each";
		}
		String count;
		String naming;
		if (profiles.size() == 1)
		{
			count = "1 cell";
			naming = "named by the test";
			if (!prefix.isEmpty())
				naming = "named `" + prefix + "<test>`";
		}
		else
		{
			count = profiles.size() + " cells";
			naming = "named `" + prefix + "<test>@<profile>`";
		}
		return tests + String.join(", ", testIds) + " (" + count + each + ", " + naming + "): " +
			labels(profiles);
	}

	/**
	 * Returns the labels of profiles.
	 *
	 * @param profiles the profiles
	 * @return the labels, separated by commas
	 */
	private static String labels(List<TrialProfile> profiles)
	{
		return String.join(", ", profiles.stream().map(TrialProfile::label).toList());
	}

	/**
	 * Computes each prompt test's profile set and the resulting cells.
	 * <p>
	 * A test's profile set comes from the files that supply the requirements that the proposal maps to its assertions.
	 * When every such file binds the same complete profile, the test has one cell under that profile. Otherwise it has
	 * one cell for each inventory profile that matches every dimension that the files all bind to the same value; if no
	 * inventory profile matches, the bound values are used as written. The user's restriction then narrows each set.
	 *
	 * @param inventory the harness's profile inventory
	 * @param ranking the model families ranked by advertised capability
	 * @param bindings every prompt file that binds a model or an effort
	 * @param restriction the patterns that the user restricted the profile set to, or empty
	 * @param described the patterns of the profiles that the user described as the certification scope, or empty
	 * @param requirements every requirement of the contract
	 * @param tests every prompt test of the contract
	 * @return the trial profiles, without a certification scope
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a restriction pattern binds nothing or matches no inventory profile, the
	 *   restriction leaves a test without a profile, a test's prompt files bind a profile that no inventory profile
	 *   matches and leave a dimension open, or a test names a requirement that {@code requirements} does not list
	 */
	public static TrialProfiles compute(ProfileCatalog inventory, CapabilityRanking ranking,
		List<ProfileBinding> bindings, List<TrialProfile> restriction, List<TrialProfile> described,
		List<Requirement> requirements, List<PromptTest> tests)
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(ranking, "ranking").isNotNull();
		requireThat(described, "described").isNotNull();
		requireThat(bindings, "bindings").isNotNull();
		requireThat(restriction, "restriction").isNotNull();
		requireThat(requirements, "requirements").isNotNull();
		requireThat(tests, "tests").isNotNull();
		for (TrialProfile pattern : restriction)
			requireRestrictable(inventory, pattern);
		Map<String, String> sources = new HashMap<>();
		for (Requirement requirement : requirements)
			sources.put(requirement.id(), requirement.source());
		Map<String, TrialProfile> bound = new HashMap<>();
		for (ProfileBinding binding : bindings)
			bound.put(binding.file(), binding.profile());

		List<Cell> cells = new ArrayList<>();
		for (PromptTest test : tests)
		{
			Set<String> files = new LinkedHashSet<>();
			for (String requirementId : test.requirementIds())
			{
				String source = sources.get(requirementId);
				if (source == null)
				{
					throw new IllegalArgumentException("The test '" + test.id() + "' checks the requirement '" +
						requirementId + "', which the contract does not list");
				}
				files.add(source);
			}
			List<TrialProfile> available = profileSet(test.id(), inventory, files, bound);
			List<TrialProfile> set = restrict(available, restriction);
			if (set.isEmpty())
			{
				throw new IllegalArgumentException("The profile restriction leaves the test '" + test.id() +
					"' with no trial profile. Its profile set is " +
					available.stream().map(TrialProfile::label).toList());
			}
			if (set.size() == 1)
				cells.add(new Cell(test.id(), test.id(), set.getFirst()));
			else
			{
				for (TrialProfile profile : set)
					cells.add(new Cell(test.id() + "@" + profile.label(), test.id(), profile));
			}
		}
		return new TrialProfiles(inventory, ranking, bindings, restriction, described, null, cells);
	}

	/**
	 * Returns the profile set of the files that supply a test's requirements, before any restriction.
	 *
	 * @param testId the test's identifier
	 * @param inventory the harness's profile inventory
	 * @param files the files
	 * @param bound the profile that each binding file binds
	 * @return the matching profiles in inventory order, or the files' shared selections when no inventory profile
	 *   matches and they name both a model and an effort
	 * @throws IllegalArgumentException if no inventory profile matches the files' shared selections and they leave a
	 *   dimension open, so that no profile that names both dimensions can be derived
	 */
	private static List<TrialProfile> profileSet(String testId, ProfileCatalog inventory, Set<String> files,
		Map<String, TrialProfile> bound)
	{
		TrialProfile fixed = new TrialProfile(common(files, bound, TrialProfile::model),
			common(files, bound, TrialProfile::effort));
		if (fixed.isComplete())
			return List.of(fixed);
		List<TrialProfile> matching = inventory.profiles().stream().filter(profile -> profile.matches(fixed)).
			toList();
		if (matching.isEmpty())
		{
			String open;
			if (fixed.model().isEmpty())
				open = "model";
			else
				open = "effort";
			throw new IllegalArgumentException("The prompt files of the test '" + testId + "' bind " + fixed.label() +
				", which no inventory profile matches, and leave its " + open + " open. A trial profile names both a " +
				"model and an effort: bind the " + open + " as well.");
		}
		return matching;
	}

	/**
	 * Returns the value that every file binds one dimension to.
	 *
	 * @param files the files
	 * @param bound the profile that each binding file binds
	 * @param dimension returns one dimension of a profile
	 * @return the common value, or empty if there are no files, a file leaves the dimension open or two files bind
	 *   different values
	 */
	private static String common(Set<String> files, Map<String, TrialProfile> bound,
		Function<TrialProfile, String> dimension)
	{
		String common = null;
		for (String file : files)
		{
			String value = dimension.apply(bound.getOrDefault(file, TrialProfile.UNBOUND));
			if (value.isEmpty() || (common != null && !common.equals(value)))
				return "";
			common = value;
		}
		if (common == null)
			return "";
		return common;
	}

	/**
	 * Narrows a profile set to the profiles that match a restriction.
	 *
	 * @param set the profile set
	 * @param restriction the restriction patterns, or empty for no restriction
	 * @return {@code set} when unrestricted, otherwise its profiles that match a pattern
	 */
	private static List<TrialProfile> restrict(List<TrialProfile> set, List<TrialProfile> restriction)
	{
		if (restriction.isEmpty())
			return set;
		return set.stream().filter(profile -> restriction.stream().anyMatch(profile::matches)).toList();
	}

	/**
	 * Returns the inventory models whose family the capability ranking does not list.
	 *
	 * @return the models, each once, in inventory order
	 */
	public List<String> unranked()
	{
		if (!byEvaluator.isEmpty())
			return byEvaluator.values().stream().flatMap(local -> local.unranked().stream()).distinct().toList();
		return ranking.unranked(inventory);
	}

	/**
	 * Returns these trial profiles with a certification scope.
	 *
	 * @param scope the certification scope
	 * @return the trial profiles; the described patterns are kept only for {@link CertificationScope#DESCRIBED}
	 * @throws NullPointerException if {@code scope} is null
	 * @throws IllegalArgumentException if {@code scope} is {@link CertificationScope#DESCRIBED} and the user described
	 *   no profile, or an inventory model's family is missing from the capability ranking
	 */
	public TrialProfiles withScope(CertificationScope scope)
	{
		requireThat(scope, "scope").isNotNull();
		if (!byEvaluator.isEmpty())
		{
			Map<Harness, TrialProfiles> scoped = new EnumMap<>(Harness.class);
			byEvaluator.forEach((evaluator, local) -> scoped.put(evaluator, local.withScope(scope)));
			return forEvaluators(scoped);
		}
		List<TrialProfile> kept = described;
		if (scope != CertificationScope.DESCRIBED)
			kept = List.of();
		return new TrialProfiles(inventory, ranking, bindings, restriction, kept, scope, cells);
	}

	/**
	 * Looks up an evaluator's inventory, ranking and cells within these profiles.
	 *
	 * @param evaluator the requested evaluator
	 * @return the evaluator's profile set, or this set if assignment has not happened yet
	 * @throws NullPointerException     if {@code evaluator} is null
	 * @throws IllegalArgumentException if assigned profiles do not contain the requested evaluator
	 */
	public TrialProfiles forEvaluator(Harness evaluator)
	{
		requireThat(evaluator, "evaluator").isNotNull();
		if (byEvaluator.isEmpty() && cells.getFirst().evaluator().orElse(evaluator) == evaluator)
			return this;
		TrialProfiles local = byEvaluator.get(evaluator);
		if (local == null)
			throw new IllegalArgumentException("No profile set for evaluation harness " + evaluator.commandName());
		return local;
	}

	/**
	 * Returns a prompt test's cells.
	 *
	 * @param testId the test
	 * @return the cells, in contract order; empty if the test has none
	 * @throws NullPointerException if {@code testId} is null
	 */
	public List<Cell> cellsOf(String testId)
	{
		requireThat(testId, "testId").isNotNull();
		return cells.stream().filter(cell -> cell.testId().equals(testId)).toList();
	}

	/**
	 * Finds a cell: a contract cell with the identifier, or else, for an identifier that ends with
	 * {@code /tool-output-} and a positive token count, that contract cell under the tool-output token limit.
	 *
	 * @param id a cell identifier
	 * @return the cell, or empty if there is none
	 * @throws NullPointerException if {@code id} is null
	 */
	public Optional<Cell> findCell(String id)
	{
		requireThat(id, "id").isNotNull();
		Optional<Cell> contractCell = findContractCell(id);
		if (contractCell.isPresent())
			return contractCell;
		Matcher limited = LIMITED_CELL.matcher(id);
		if (!limited.matches())
			return Optional.empty();
		int tokens;
		try
		{
			tokens = Integer.parseInt(limited.group(2));
		}
		catch (NumberFormatException _)
		{
			// The token count exceeds the largest limit
			return Optional.empty();
		}
		return findContractCell(limited.group(1)).map(cell -> cell.withToolOutputTokenLimit(tokens));
	}

	/**
	 * Finds a cell of the contract.
	 *
	 * @param id a cell identifier
	 * @return the cell, or empty if the contract has none with that identifier
	 */
	private Optional<Cell> findContractCell(String id)
	{
		return cells.stream().filter(cell -> cell.id().equals(id)).findFirst();
	}
}
