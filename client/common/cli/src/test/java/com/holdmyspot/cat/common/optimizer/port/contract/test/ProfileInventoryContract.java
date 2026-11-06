package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.CapabilityRanking;
import com.holdmyspot.cat.common.optimizer.contract.ProfileCatalog;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The behaviour that every {@link ProfileInventory} implementation must show, one check per operation.
 * <p>
 * A test class gives the contract a {@link Subject} for the implementation under test and runs each check from a test
 * method of its own that carries the requirement tags. The checks are plain methods rather than inherited test
 * methods because the package that holds them is not opened to the test framework.
 */
public final class ProfileInventoryContract
{
	private final Subject subject;

	/**
	 * Creates the contract.
	 *
	 * @param subject the implementation under test
	 * @throws NullPointerException if {@code subject} is null
	 */
	public ProfileInventoryContract(Subject subject)
	{
		requireThat(subject, "subject").isNotNull();
		this.subject = subject;
	}

	/**
	 * An implementation under test, with the binding files that suit its harness.
	 */
	public interface Subject
	{
		/**
		 * Returns the inventory under test.
		 *
		 * @return the inventory
		 */
		ProfileInventory port();

		/**
		 * Returns the path of a file that the harness reads a binding from.
		 *
		 * @return the path, relative to the project
		 */
		Path bindingFile();

		/**
		 * Returns the content of a binding file that binds a profile.
		 *
		 * @param profile the profile; an empty dimension is left out of the file
		 * @return the content
		 */
		String content(TrialProfile profile);

		/**
		 * Returns a model that the harness runs sessions under.
		 *
		 * @return the model's name
		 */
		String supportedModel();

		/**
		 * Returns a model that the harness does not run sessions under.
		 *
		 * @return the model's name, or empty if the harness accepts every model
		 */
		Optional<String> unsupportedModel();

		/**
		 * Returns the bytes of each tool output that the harness is known to give a trial whole.
		 *
		 * @param tokenLimit the profile's tool-output token limit, or {@code 0} if it has none
		 * @return the number of bytes, or empty if the harness's limit is not known
		 */
		OptionalLong toolOutputBytes(int tokenLimit);
	}

	/**
	 * Checks that the inventory lists at least one profile and names its source.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	public void listsProfilesWithSource() throws IOException
	{
		ProfileCatalog inventory = subject.port().inventory();
		requireThat(inventory.source(), "source").isNotBlank();
		requireThat(inventory.profiles(), "profiles").isNotEmpty();
	}

	/**
	 * Checks that only the harness's binding files can bind a profile.
	 */
	public void bindsOnlyBindingFiles()
	{
		ProfileInventory port = subject.port();
		requireThat(port.canBind(subject.bindingFile()), "canBind(bindingFile)").isTrue();
		for (String name : new String[]{"CLAUDE.md", "AGENTS.md", ".claude/rules/style.md", "notes/model.toml"})
			requireThat(port.canBind(Path.of(name)), "canBind(" + name + ")").isFalse();
	}

	/**
	 * Checks that a binding file binds what it names: both dimensions, one dimension, or nothing.
	 */
	public void readsCompletePartialAndEmptyBindings()
	{
		ProfileInventory port = subject.port();
		for (TrialProfile profile : new TrialProfile[]{new TrialProfile("model-a", "high"),
			new TrialProfile("model-a", ""), new TrialProfile("", "low"), TrialProfile.UNBOUND})
		{
			requireThat(port.binding(subject.content(profile)), "binding(" + profile + ")").isEqualTo(profile);
		}
	}

	/**
	 * Checks that the role profile binds a dimension.
	 *
	 * @throws IOException if the role profile cannot be read
	 */
	public void roleProfileIsBound() throws IOException
	{
		requireThat(subject.port().roleProfile().isBound(), "roleProfile().isBound()").isTrue();
	}

	/**
	 * Checks that the ranking names a source and ranks the family of every model that the inventory lists.
	 *
	 * @throws IOException if the inventory or the ranking cannot be read
	 */
	public void ranksEveryListedModel() throws IOException
	{
		CapabilityRanking ranking = subject.port().ranking();
		requireThat(ranking.source(), "source").isNotBlank();
		for (TrialProfile profile : subject.port().inventory().profiles())
		{
			requireThat(ranking.families(), "families").contains(CapabilityRanking.family(profile.model()));
		}
	}

	/**
	 * Checks that the harness runs the models that its inventory lists and the model that the subject supports, and
	 * does not run the one that the subject does not support.
	 *
	 * @throws IOException if the inventory cannot be read
	 */
	public void supportsTheModelsItLists() throws IOException
	{
		ProfileInventory port = subject.port();
		for (TrialProfile profile : port.inventory().profiles())
			requireThat(port.supportsModel(profile.model()), "supportsModel(" + profile.model() + ")").isTrue();
		requireThat(port.supportsModel(subject.supportedModel()), "supportsModel(supportedModel)").isTrue();
		Optional<String> unsupported = subject.unsupportedModel();
		if (unsupported.isPresent())
			requireThat(port.supportsModel(unsupported.get()), "supportsModel(unsupportedModel)").isFalse();
	}

	/**
	 * Checks the bytes of each tool output that a trial of a profile receives whole, for a profile with no limit, a
	 * limit below the harness's and a limit above it.
	 */
	public void reportsTheToolOutputBytesOfAProfile()
	{
		ProfileInventory port = subject.port();
		for (int tokenLimit : new int[]{0, 1_000, 20_000})
		{
			TrialProfile profile = new TrialProfile("model-a", "high", tokenLimit);
			requireThat(port.toolOutputBytes(profile), "toolOutputBytes(" + tokenLimit + ")").
				isEqualTo(subject.toolOutputBytes(tokenLimit));
		}
	}
}
