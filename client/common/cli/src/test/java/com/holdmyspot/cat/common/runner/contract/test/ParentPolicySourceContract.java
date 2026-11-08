package com.holdmyspot.cat.common.runner.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.RealPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * The behaviour that every {@link ParentPolicySource} shares: it names its harness, tells the user which settings
 * entries quote the writes and hosts that a session lacks, reads the policy that the session's settings give its
 * commands, gives a thread that has a policy of its own that policy and every other thread the session's, fails to
 * read settings that cannot be read, and refuses settings that cannot be parsed and a session whose harness process
 * has ended. A test class of each source extends this class and gives it a session
 * of its harness whose settings the test changes.
 */
public abstract class ParentPolicySourceContract
{
	/**
	 * A thread identifier, in the form that Codex gives a thread, that names no thread of the session.
	 */
	private static final String UNKNOWN_THREAD = "01a1a8f0-0000-7000-8000-000000000000";

	/**
	 * Creates the contract.
	 */
	protected ParentPolicySourceContract()
	{
	}

	/**
	 * A harness session, the settings that give its commands their policy, and the source under test that reads
	 * them.
	 */
	public interface Session extends AutoCloseable
	{
		/**
		 * Returns the source of the session's policy.
		 *
		 * @return the source
		 */
		ParentPolicySource source();

		/**
		 * Returns a folder that the harness does not let the session's commands write unless its settings name it.
		 *
		 * @return an existing, empty folder
		 */
		Path folder();

		/**
		 * Changes the session's settings so that they let its commands write some folders besides those that the
		 * harness always lets them write.
		 *
		 * @param writes the folders, inside {@link #folder()}
		 * @throws IOException if the settings cannot be written
		 */
		void allowWrites(List<Path> writes) throws IOException;

		/**
		 * Gives a thread of the session a policy of its own that writes nothing, if the harness gives its threads
		 * policies of their own.
		 *
		 * @return the thread's identifier, or empty if every thread of the harness's sessions has the session's policy
		 * @throws IOException if the thread's records cannot be written
		 */
		Optional<String> readOnlyThread() throws IOException;

		/**
		 * Replaces the session's settings file with something that cannot be read as a file.
		 *
		 * @throws IOException if the settings cannot be replaced
		 */
		void unreadableSettings() throws IOException;

		/**
		 * Replaces the content of the session's settings file with text that the harness cannot parse.
		 *
		 * @throws IOException if the settings cannot be written
		 */
		void unparsableSettings() throws IOException;

		/**
		 * Ends the session's harness process.
		 */
		void end();

		@Override
		void close() throws IOException;
	}

	/**
	 * Returns the name of the session's harness as its user knows it.
	 *
	 * @return the name
	 */
	protected abstract String harnessName();

	/**
	 * Returns a value as the harness's settings quote it.
	 *
	 * @param value a path or a host
	 * @return the quoted value
	 */
	protected abstract String quoted(String value);

	/**
	 * Returns whether a later reading gives the writes that the settings have added since the first reading.
	 *
	 * @return {@code false} if the source never gives more than its first reading gave
	 */
	protected abstract boolean followsWidenedSettings();

	/**
	 * Creates a session whose settings let its commands write only what the harness always lets them write, and whose
	 * source has read nothing.
	 *
	 * @return the session
	 * @throws IOException if the session's files cannot be created
	 */
	protected abstract Session session() throws IOException;

	/**
	 * Verifies that the source names its harness.
	 *
	 * @throws IOException if the session cannot be created
	 */
	@Test
	public void namesHarness() throws IOException
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			requireThat(session.source().harnessName(), "harnessName").isEqualTo(harnessName());
		}
	}

	/**
	 * Verifies that the instruction to add missing writes and hosts starts with {@code add}, has no final period and
	 * quotes each path and host, whether both kinds, only writes or only hosts are missing.
	 *
	 * @throws IOException if the session cannot be created
	 */
	@Test
	public void entriesQuoteEachPathAndHost() throws IOException
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			Path cache = session.folder().resolve("cache");
			Path repository = session.folder().resolve("repository");
			List<Path> writes = List.of(cache, repository);
			Set<String> hosts = Set.of("a.example.com", "b.example.com");
			for (String entries : List.of(session.source().permissionEntries(writes, hosts),
				session.source().permissionEntries(writes, Set.of()),
				session.source().permissionEntries(List.of(), hosts)))
			{
				requireThat(entries, "entries").startsWith("add ").doesNotEndWith(".");
			}
			requireThat(session.source().permissionEntries(writes, hosts), "entries").
				contains(quoted(cache.toString())).contains(quoted(repository.toString())).
				contains(quoted("a.example.com")).contains(quoted("b.example.com"));
			requireThat(session.source().permissionEntries(writes, Set.of()), "writesOnly").
				contains(quoted(cache.toString())).contains(quoted(repository.toString())).
				doesNotContain(quoted("a.example.com"));
			requireThat(session.source().permissionEntries(List.of(), hosts), "hostsOnly").
				contains(quoted("a.example.com")).contains(quoted("b.example.com")).
				doesNotContain(quoted(cache.toString()));
		}
	}

	/**
	 * Verifies that the source refuses to name entries for nothing.
	 *
	 * @throws IOException if the session cannot be created
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesEntriesForNothing() throws IOException
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			session.source().permissionEntries(List.of(), Set.of());
		}
	}

	/**
	 * Verifies that the source reads the writes that the session's settings give its commands, for its main thread
	 * and for a thread that is not one of the session's.
	 *
	 * @throws IOException   if the session cannot be created
	 * @throws PolicyRefusal if a reading is refused
	 */
	@Test
	public void readsSessionPolicy() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			Path allowed = Files.createDirectory(session.folder().resolve("allowed"));
			Path other = Files.createDirectory(session.folder().resolve("other"));
			session.allowWrites(List.of(allowed));

			ParentPolicy main = session.source().read(Optional.empty());
			requireThat(main.sandboxed(), "sandboxed").isTrue();
			requireThat(writes(main, allowed), "writesAllowed").isTrue();
			requireThat(writes(main, other), "writesOther").isFalse();

			ParentPolicy unknown = session.source().read(Optional.of(UNKNOWN_THREAD));
			requireThat(writes(unknown, allowed), "unknownThreadWritesAllowed").isTrue();
			requireThat(writes(unknown, other), "unknownThreadWritesOther").isFalse();
		}
	}

	/**
	 * Verifies that a later reading loses a write that the session's settings no longer give.
	 *
	 * @throws IOException   if the session cannot be created
	 * @throws PolicyRefusal if a reading is refused
	 */
	@Test
	public void laterReadingFollowsNarrowedSettings() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			Path kept = Files.createDirectory(session.folder().resolve("kept"));
			Path dropped = Files.createDirectory(session.folder().resolve("dropped"));
			session.allowWrites(List.of(kept, dropped));
			requireThat(writes(session.source().read(Optional.empty()), dropped), "firstWritesDropped").isTrue();

			session.allowWrites(List.of(kept));

			ParentPolicy later = session.source().read(Optional.empty());
			requireThat(writes(later, kept), "writesKept").isTrue();
			requireThat(writes(later, dropped), "writesDropped").isFalse();
		}
	}

	/**
	 * Verifies that a later reading gives a write that the session's settings have added only if the source follows
	 * widened settings.
	 *
	 * @throws IOException   if the session cannot be created
	 * @throws PolicyRefusal if a reading is refused
	 */
	@Test
	public void laterReadingOfWidenedSettings() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			Path kept = Files.createDirectory(session.folder().resolve("kept"));
			Path added = Files.createDirectory(session.folder().resolve("added"));
			session.allowWrites(List.of(kept));
			requireThat(writes(session.source().read(Optional.empty()), added), "firstWritesAdded").isFalse();

			session.allowWrites(List.of(kept, added));

			ParentPolicy later = session.source().read(Optional.empty());
			requireThat(writes(later, kept), "writesKept").isTrue();
			requireThat(writes(later, added), "writesAdded").isEqualTo(followsWidenedSettings());
		}
	}

	/**
	 * Verifies that a thread with a policy of its own reads that policy while the session's main thread keeps the
	 * session's, and that in a harness whose threads share the session's policy every thread reads the session's.
	 *
	 * @throws IOException   if the session cannot be created
	 * @throws PolicyRefusal if a reading is refused
	 */
	@Test
	public void threadReadsItsOwnPolicy() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			Path allowed = Files.createDirectory(session.folder().resolve("allowed"));
			session.allowWrites(List.of(allowed));
			Optional<String> thread = session.readOnlyThread();

			ParentPolicy threadPolicy = session.source().read(Optional.of(thread.orElse(UNKNOWN_THREAD)));
			requireThat(writes(threadPolicy, allowed), "threadWritesAllowed").isEqualTo(thread.isEmpty());
			requireThat(writes(session.source().read(Optional.empty()), allowed), "mainWritesAllowed").isTrue();
		}
	}

	/**
	 * Verifies that a reading fails with an I/O error, not a refusal, when the session's settings cannot be read.
	 *
	 * @throws IOException if the session cannot be created
	 */
	@Test
	public void failsReadingUnreadableSettings() throws IOException
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			session.unreadableSettings();

			try
			{
				ParentPolicy policy = session.source().read(Optional.empty());
				throw new AssertionError("The reading did not fail: " + policy);
			}
			catch (PolicyRefusal e)
			{
				throw new AssertionError("The reading was refused instead of failing", e);
			}
			catch (IOException _)
			{
				// The reading failed, as it must
			}
		}
	}

	/**
	 * Verifies that a reading is refused when the session's settings cannot be parsed.
	 *
	 * @throws IOException if the session cannot be created
	 */
	@Test
	public void refusesUnparsableSettings() throws IOException
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			session.unparsableSettings();

			try
			{
				ParentPolicy policy = session.source().read(Optional.empty());
				throw new AssertionError("The reading was not refused: " + policy);
			}
			catch (PolicyRefusal _)
			{
				// The source refused, as it must
			}
		}
	}

	/**
	 * Verifies that the source refuses to read once the session's harness process has ended.
	 *
	 * @throws IOException   if the session cannot be created
	 * @throws PolicyRefusal if the first reading is refused
	 */
	@Test
	public void refusesEndedSession() throws IOException, PolicyRefusal
	{
		// Requirement: R-I11
		try (Session session = session())
		{
			session.source().read(Optional.empty());

			session.end();

			try
			{
				ParentPolicy policy = session.source().read(Optional.empty());
				throw new AssertionError("The reading was not refused: " + policy);
			}
			catch (PolicyRefusal _)
			{
				// The source refused, as it must
			}
		}
	}

	/**
	 * Returns whether a policy lets the commands write a folder.
	 *
	 * @param policy a policy
	 * @param folder an existing folder
	 * @return {@code true} if the folder lies inside a write root and inside no write denial
	 * @throws IOException if a path cannot be resolved
	 */
	private static boolean writes(ParentPolicy policy, Path folder) throws IOException
	{
		if (!policy.sandboxed())
			return true;
		Path real = RealPaths.resolve(folder);
		return RealPaths.isInsideAny(real, RealPaths.resolveAll(policy.writeRoots())) &&
			!RealPaths.isInsideAny(real, RealPaths.resolveAll(policy.writeDenials()));
	}
}
