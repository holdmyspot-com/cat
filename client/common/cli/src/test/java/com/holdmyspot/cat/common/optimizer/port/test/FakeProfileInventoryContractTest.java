package com.holdmyspot.cat.common.optimizer.port.test;

import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.contract.test.ProfileInventoryContract;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import org.testng.annotations.Test;

/**
 * Runs the {@link ProfileInventoryContract} against {@link FakeProfileInventory}, so that the fake that the rest of
 * the suite relies on shows the behaviour that the real adapters must show.
 */
public final class FakeProfileInventoryContractTest
{
	private static final String REJECTED_MODEL = "rejected-model";

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
		port.rejectModel(REJECTED_MODEL);
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

			@Override
			public String supportedModel()
			{
				return "model-a";
			}

			@Override
			public Optional<String> unsupportedModel()
			{
				return Optional.of(REJECTED_MODEL);
			}

			@Override
			public OptionalLong toolOutputBytes(int tokenLimit)
			{
				// The fake answers as Codex does
				if (tokenLimit == 0 || tokenLimit > 10_000)
					return OptionalLong.of(40_000);
				return OptionalLong.of(4L * tokenLimit);
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

	/**
	 * Verifies that the fake ranks the family of every model that it lists.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void ranksEveryListedModel() throws IOException
	{
		// Requirement: R-C10
		contract().ranksEveryListedModel();
	}

	/**
	 * Verifies that the fake supports its listed models and rejects the one that the test rejected.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void supportsTheModelsItLists() throws IOException
	{
		// Requirement: R-C10
		contract().supportsTheModelsItLists();
	}

	/**
	 * Verifies the bytes of each tool output that the fake reports.
	 */
	@Test
	public void reportsTheToolOutputBytesOfAProfile()
	{
		// Requirement: R-C10
		contract().reportsTheToolOutputBytesOfAProfile();
	}
}
