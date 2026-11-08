package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.contract.test.ParentPolicySourceContract;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the {@link ParentPolicySource} contract against {@link FakeParentPolicySource}, so that the launcher tests
 * that read their sessions' policies through it see what the session launchers' sources do.
 */
public final class FakeParentPolicySourceTest extends ParentPolicySourceContract
{
	private static final String HARNESS_NAME = "Harness A";

	/**
	 * Creates the test.
	 */
	public FakeParentPolicySourceTest()
	{
	}

	@Override
	protected String harnessName()
	{
		return HARNESS_NAME;
	}

	@Override
	protected String quoted(String value)
	{
		return "\"" + value + "\"";
	}

	@Override
	protected boolean followsWidenedSettings()
	{
		return true;
	}

	@Override
	protected Session session() throws IOException
	{
		return new FakeSession();
	}

	/**
	 * A session whose policy source is the fake.
	 */
	private static final class FakeSession implements Session
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path folder;
		private final FakeParentPolicySource source = new FakeParentPolicySource(HARNESS_NAME, writing(List.of()));

		/**
		 * Creates a session.
		 *
		 * @throws IOException if the session's folder cannot be created
		 */
		FakeSession() throws IOException
		{
			this.folder = cleanup.add(Files.createTempDirectory("fake-policy-"));
		}

		@Override
		public ParentPolicySource source()
		{
			return source;
		}

		@Override
		public Path folder()
		{
			return folder;
		}

		@Override
		public void allowWrites(List<Path> writes)
		{
			source.allow(writing(writes));
		}

		@Override
		public Optional<String> readOnlyThread()
		{
			String thread = "thread-a";
			source.allowThread(thread, writing(List.of()));
			return Optional.of(thread);
		}

		@Override
		public void unreadableSettings()
		{
			source.failReading(new IOException(folder.resolve("settings") + ": Is a directory"));
		}

		@Override
		public void unparsableSettings()
		{
			source.refuse(new PolicyRefusal("the settings file " + folder.resolve("settings") + " cannot be parsed",
				"Fix the file, then start a new session"));
		}

		@Override
		public void end()
		{
			source.refuse(new PolicyRefusal("the session has ended", "Start a new session"));
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}

	/**
	 * Returns a sandboxed policy that writes some folders and reaches no host.
	 *
	 * @param writes the folders
	 * @return the policy
	 */
	private static ParentPolicy writing(List<Path> writes)
	{
		return new ParentPolicy(true, writes, List.of(), List.of(), List.of(), HostAccess.listed(Set.of()), false,
			List.of());
	}
}
