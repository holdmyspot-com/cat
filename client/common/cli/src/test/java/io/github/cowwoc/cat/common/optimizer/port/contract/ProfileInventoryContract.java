package io.github.cowwoc.cat.common.optimizer.port.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;

import java.io.IOException;
import java.nio.file.Path;

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
			new TrialProfile("model-a", ""), new TrialProfile("", "low"), TrialProfile.HARNESS_DEFAULT})
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
}
