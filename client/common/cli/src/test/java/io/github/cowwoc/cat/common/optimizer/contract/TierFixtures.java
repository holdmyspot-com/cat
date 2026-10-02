package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds contracts whose categories have several cells per model, for tests of development cell tiering.
 */
public final class TierFixtures
{
	/**
	 * The cell of the {@code formatting} category under {@code big/low} in {@link #tieredContract()}: the lowest
	 * effort of model {@code big}.
	 */
	public static final String BIG_LOW = "formatting@big/low";
	/**
	 * The cell of the {@code formatting} category under {@code big/medium} in {@link #tieredContract()}: neither the
	 * lowest nor the highest effort of model {@code big}.
	 */
	public static final String BIG_MEDIUM = "formatting@big/medium";
	/**
	 * The cell of the {@code formatting} category under {@code big/high} in {@link #tieredContract()}: neither the
	 * lowest nor the highest effort of model {@code big}.
	 */
	public static final String BIG_HIGH = "formatting@big/high";
	/**
	 * The cell of the {@code formatting} category under {@code big/xhigh} in {@link #tieredContract()}: the highest
	 * effort of model {@code big}.
	 */
	public static final String BIG_XHIGH = "formatting@big/xhigh";
	/**
	 * The cell of the {@code formatting} category under {@code small/high} in {@link #tieredContract()}: the only
	 * effort of model {@code small}, so both its lowest and its highest.
	 */
	public static final String SMALL_HIGH = "formatting@small/high";
	/**
	 * The only cell of the {@code safety} category in {@link #tieredContract()}, whose requirement's file binds
	 * {@code big/high}.
	 */
	public static final String SAFETY = "safety";
	/**
	 * An inventory that lists model {@code big} under four efforts, lowest first, and model {@code small} under one.
	 */
	public static final ProfileCatalog TIERED_PROFILES = new ProfileCatalog("test inventory of five profiles",
		List.of(new TrialProfile("big", "low"), new TrialProfile("big", "medium"), new TrialProfile("big", "high"),
			new TrialProfile("big", "xhigh"), new TrialProfile("small", "high")));
	/**
	 * The reasoning efforts that each model of the recorded Codex inventory lists, in listing order.
	 * <p>
	 * This copies the inventory that {@code CodexProfileInventoryTest.keepsListedModelsWithTheirEfforts} reads from
	 * the recorded output of {@code codex debug models} of codex-cli 0.159.1, which the common module cannot read.
	 */
	public static final Map<String, List<String>> CODEX_EFFORTS = codexEfforts();
	/**
	 * The recorded Codex inventory of 44 profiles, in listing order.
	 */
	public static final ProfileCatalog CODEX_PROFILES = new ProfileCatalog("`codex debug models` of " +
		"codex-cli 0.159.1+23", profiles(CODEX_EFFORTS));

	/**
	 * Returns the efforts of each model of the recorded Codex inventory.
	 *
	 * @return the efforts, in listing order
	 */
	private static Map<String, List<String>> codexEfforts()
	{
		List<String> ultra = List.of("low", "medium", "high", "xhigh", "max", "ultra");
		List<String> max = List.of("low", "medium", "high", "xhigh", "max");
		Map<String, List<String>> efforts = new LinkedHashMap<>();
		efforts.put("gpt-6-astra", ultra);
		efforts.put("gpt-6.1-sol", ultra);
		efforts.put("gpt-6-sol", ultra);
		efforts.put("gpt-6-luna", max);
		efforts.put("gpt-5.6-sol", ultra);
		efforts.put("gpt-5.6-terra", ultra);
		efforts.put("gpt-5.6-luna", max);
		efforts.put("gpt-5.5", List.of("low", "medium", "high", "xhigh"));
		return efforts;
	}

	/**
	 * Returns the profiles of each model's efforts.
	 *
	 * @param efforts the efforts of each model
	 * @return the profiles, model by model in listing order
	 */
	private static List<TrialProfile> profiles(Map<String, List<String>> efforts)
	{
		List<TrialProfile> profiles = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : efforts.entrySet())
		{
			for (String effort : entry.getValue())
				profiles.add(new TrialProfile(entry.getKey(), effort));
		}
		return profiles;
	}

	/**
	 * Returns the contract of {@link ContractFixtures#contract()} under {@link #TIERED_PROFILES}, where the
	 * {@code safety} requirement's file binds {@code big/high}. The {@code formatting} category has the cells
	 * {@link #BIG_LOW}, {@link #BIG_MEDIUM}, {@link #BIG_HIGH}, {@link #BIG_XHIGH} and {@link #SMALL_HIGH}, in that
	 * order, and the {@code safety} category has the one cell {@link #SAFETY}.
	 *
	 * @return the contract
	 */
	public static Contract tieredContract()
	{
		return contract(TIERED_PROFILES, new TrialProfile("big", "high"));
	}

	/**
	 * Returns the contract of {@link ContractFixtures#contract()} under an inventory, where the {@code formatting}
	 * category binds nothing, so it has one cell per inventory profile, and the {@code safety} requirement's file
	 * binds a complete profile, so that category has one cell.
	 *
	 * @param inventory the profile inventory
	 * @param safety the profile that the {@code safety} requirement's file binds
	 * @return the contract
	 */
	public static Contract contract(ProfileCatalog inventory, TrialProfile safety)
	{
		Contract fixture = ContractFixtures.contract();
		String safetySource = fixture.requirements().stream().filter(requirement -> requirement.id().equals("r2")).
			findFirst().orElseThrow().source();
		TrialProfiles profiles = TrialProfiles.compute(inventory, List.of(new ProfileBinding(safetySource, safety)),
			List.of(), fixture.requirements(), fixture.categories(), Set.of(Requirement.CORE_TASK.id()));
		return new Contract(fixture.requirements(), fixture.categories(), profiles, fixture.judge(),
			fixture.fixtures(), fixture.limitations());
	}

	/**
	 * Prevents instantiation.
	 */
	private TierFixtures()
	{
	}
}
