package io.github.cowwoc.cat.common.optimizer.port.test;

import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.ProfileInventoryContract;

import java.io.IOException;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Runs the {@link ProfileInventoryContract} against {@link FakeProfileInventory}, so that the fake that the rest of
 * the suite relies on shows the behaviour that the real adapters must show.
 */
public final class FakeProfileInventoryContractTest
{
	/**
	 * Creates the test.
	 */
	public FakeProfileInventoryContractTest()
	{
	}

	/**
	 * Returns the contract over a fresh fake.
	 *
	 * @return the contract
	 */
	private static ProfileInventoryContract contract()
	{
		FakeProfileInventory port = new FakeProfileInventory();
		return new ProfileInventoryContract(new ProfileInventoryContract.Subject()
		{
			@Override
			public ProfileInventory port()
			{
				return port;
			}

			@Override
			public Path bindingFile()
			{
				return Path.of("tools/agents/reviewer.agent");
			}

			@Override
			public String content(TrialProfile profile)
			{
				return FakeProfileInventory.content(profile);
			}
		});
	}

	/**
	 * Verifies that the fake lists profiles with a source.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void listsProfilesWithSource() throws IOException
	{
		// Requirement: R-C10
		contract().listsProfilesWithSource();
	}

	/**
	 * Verifies that only the fake's binding files bind a profile.
	 */
	@Test
	public void bindsOnlyBindingFiles()
	{
		// Requirement: R-C10
		contract().bindsOnlyBindingFiles();
	}

	/**
	 * Verifies that the fake reads complete, partial and empty bindings.
	 */
	@Test
	public void readsCompletePartialAndEmptyBindings()
	{
		// Requirement: R-C10
		contract().readsCompletePartialAndEmptyBindings();
	}

	/**
	 * Verifies that the fake's role profile binds a dimension.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void roleProfileIsBound() throws IOException
	{
		// Requirement: R-E17
		contract().roleProfileIsBound();
	}
}
