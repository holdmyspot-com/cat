package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.CapabilityRanking;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A {@link ProfileInventory} with a fixed inventory and role profile.
 * <p>
 * {@code ProfileInventoryContract} checks every answer of the fake, which runs against it and the real adapters.
 * <p>
 * Its binding files are the {@code .agent} files under an {@code agents} directory, whose lines {@code model=VALUE}
 * and {@code effort=VALUE} bind a dimension each.
 * <p>
 * It supports every model unless a test names one with {@link #rejectModel(String)}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeProfileInventory implements ProfileInventory
{
	/**
	 * The inventory of a fresh fake: one profile, under which every test whose files bind nothing has one cell.
	 */
	public static final ProfileCatalog INVENTORY = new ProfileCatalog("fake inventory",
		List.of(new TrialProfile("test-model", "high")));
	/**
	 * The role profile of a fresh fake.
	 */
	public static final TrialProfile ROLE = new TrialProfile("role-model", "high");
	/**
	 * The development judge candidates of a fresh fake, cheapest first.
	 */
	public static final List<TrialProfile> CANDIDATES = List.of(new TrialProfile("cheap-model", "low"),
		new TrialProfile("middle-model", "medium"));
	private final ProfileCatalog inventory;
	private final CapabilityRanking ranking;
	private final TrialProfile role;
	private final List<TrialProfile> candidates;
	private final Set<String> rejectedModels = new CopyOnWriteArraySet<>();

	/**
	 * Creates a fake with {@link #INVENTORY} and {@link #ROLE}.
	 */
	public FakeProfileInventory()
	{
		this(INVENTORY, ROLE);
	}

	/**
	 * Creates a fake.
	 *
	 * @param inventory the inventory
	 * @param role the role profile
	 * @throws NullPointerException if any of the arguments are null
	 */
	public FakeProfileInventory(ProfileCatalog inventory, TrialProfile role)
	{
		this(inventory, ContractFixtures.ranking(inventory), role);
	}

	/**
	 * Creates a fake.
	 *
	 * @param inventory the inventory
	 * @param ranking the capability ranking
	 * @param role the role profile
	 * @throws NullPointerException if any of the arguments are null
	 */
	public FakeProfileInventory(ProfileCatalog inventory, CapabilityRanking ranking, TrialProfile role)
	{
		this(inventory, ranking, role, CANDIDATES);
	}

	/**
	 * Creates a fake.
	 *
	 * @param inventory the inventory
	 * @param ranking the capability ranking
	 * @param role the role profile
	 * @param candidates the development judge candidates, cheapest first
	 * @throws NullPointerException if any of the arguments are null
	 */
	public FakeProfileInventory(ProfileCatalog inventory, CapabilityRanking ranking, TrialProfile role,
		List<TrialProfile> candidates)
	{
		requireThat(inventory, "inventory").isNotNull();
		requireThat(ranking, "ranking").isNotNull();
		requireThat(role, "role").isNotNull();
		requireThat(candidates, "candidates").isNotNull();
		this.inventory = inventory;
		this.ranking = ranking;
		this.role = role;
		this.candidates = List.copyOf(candidates);
	}

	/**
	 * Makes the fake reject a model, so that {@link #supportsModel(String)} returns {@code false} for it.
	 *
	 * @param model the model's name
	 * @throws NullPointerException if {@code model} is null
	 */
	public void rejectModel(String model)
	{
		requireThat(model, "model").isNotNull();
		rejectedModels.add(model);
	}

	/**
	 * Returns the content of a binding file.
	 *
	 * @param profile the profile to bind; an empty dimension is left out
	 * @return the content
	 */
	public static String content(TrialProfile profile)
	{
		StringBuilder content = new StringBuilder("# A fake agent\n");
		if (!profile.model().isEmpty())
			content.append("model=").append(profile.model()).append('\n');
		if (!profile.effort().isEmpty())
			content.append("effort=").append(profile.effort()).append('\n');
		return content.toString();
	}

	@Override
	public ProfileCatalog inventory()
	{
		return inventory;
	}

	@Override
	public CapabilityRanking ranking()
	{
		return ranking;
	}

	@Override
	public boolean canBind(Path path)
	{
		requireThat(path, "path").isNotNull();
		Path parent = path.getParent();
		return path.getFileName().toString().endsWith(".agent") && parent != null &&
			parent.getFileName().toString().equals("agents");
	}

	@Override
	public TrialProfile binding(String content)
	{
		requireThat(content, "content").isNotNull();
		String model = "";
		String effort = "";
		for (String line : content.split("\n"))
		{
			if (line.startsWith("model="))
				model = line.substring("model=".length());
			else if (line.startsWith("effort="))
				effort = line.substring("effort=".length());
		}
		return new TrialProfile(model, effort);
	}

	@Override
	public boolean supportsModel(String model)
	{
		requireThat(model, "model").isNotNull();
		return !rejectedModels.contains(model);
	}

	@Override
	public TrialProfile roleProfile()
	{
		return role;
	}

	@Override
	public List<TrialProfile> candidateJudgeProfiles()
	{
		return candidates;
	}

	/**
	 * Returns 4 bytes per token of a profile's tool-output token limit, up to 10,000 tokens, which is also the answer
	 * for a profile with no limit.
	 *
	 * @param profile the trial profile
	 * @return the number of bytes
	 */
	@Override
	public OptionalLong toolOutputBytes(TrialProfile profile)
	{
		requireThat(profile, "profile").isNotNull();
		long tokens = 10_000;
		if (profile.toolOutputTokenLimit() > 0)
			tokens = Math.min(tokens, profile.toolOutputTokenLimit());
		return OptionalLong.of(4 * tokens);
	}
}
