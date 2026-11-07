package com.holdmyspot.cat.common.runner.launcher.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stands in for the sandbox policy source of a harness session whose settings the test sets. Like the Codex
 * session launcher's source, each reading gives the session's policy as it is now, a thread that has a policy of its
 * own reads that policy, and every reading after the session has ended is refused.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeParentPolicySource implements ParentPolicySource
{
	private final String harnessName;
	private final Map<String, ParentPolicy> threads = new HashMap<>();
	private ParentPolicy session;
	private PolicyRefusal refusal;

	/**
	 * Creates a source.
	 *
	 * @param harnessName the name of the session's harness
	 * @param session     the policy that the session's settings give the commands of its threads that have none of
	 *                    their own
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harnessName} is blank
	 */
	public FakeParentPolicySource(String harnessName, ParentPolicy session)
	{
		requireThat(harnessName, "harnessName").isNotBlank();
		requireThat(session, "session").isNotNull();
		this.harnessName = harnessName;
		this.session = session;
	}

	/**
	 * Changes the session's settings so that later readings give a policy.
	 *
	 * @param policy the policy
	 * @throws NullPointerException if {@code policy} is null
	 */
	public void allow(ParentPolicy policy)
	{
		requireThat(policy, "policy").isNotNull();
		synchronized (this)
		{
			session = policy;
		}
	}

	/**
	 * Gives one thread of the session a policy of its own.
	 *
	 * @param thread the thread's identifier
	 * @param policy the thread's policy, which gives no more than the session's
	 * @throws NullPointerException if any of the arguments are null
	 */
	public void allowThread(String thread, ParentPolicy policy)
	{
		requireThat(thread, "thread").isNotNull();
		requireThat(policy, "policy").isNotNull();
		synchronized (this)
		{
			threads.put(thread, policy);
		}
	}

	/**
	 * Makes every later reading fail, as it does once the session's harness process has ended or its settings
	 * cannot be used.
	 *
	 * @param refusal the failure
	 * @throws NullPointerException if {@code refusal} is null
	 */
	public void refuse(PolicyRefusal refusal)
	{
		requireThat(refusal, "refusal").isNotNull();
		synchronized (this)
		{
			this.refusal = refusal;
		}
	}

	@Override
	public String harnessName()
	{
		return harnessName;
	}

	@Override
	public String permissionEntries(List<Path> writes, Set<String> hosts)
	{
		requireThat(writes, "writes").isNotNull();
		requireThat(hosts, "hosts").isNotNull();
		if (writes.isEmpty() && hosts.isEmpty())
			throw new IllegalArgumentException("writes and hosts may not both be empty");
		List<String> additions = new ArrayList<>();
		if (!writes.isEmpty())
			additions.add(quoted(writes.stream().map(Path::toString).toList()) + " to the writes");
		if (!hosts.isEmpty())
			additions.add(quoted(new TreeSet<>(hosts)) + " to the hosts");
		return "add " + String.join(" and ", additions) + " in the settings of " + harnessName;
	}

	/**
	 * Quotes values.
	 *
	 * @param values the values
	 * @return the quoted values, separated by commas
	 */
	private static String quoted(Iterable<String> values)
	{
		List<String> quoted = new ArrayList<>();
		for (String value : values)
			quoted.add("\"" + value + "\"");
		return String.join(", ", quoted);
	}

	@Override
	public ParentPolicy read(Optional<String> askingThread) throws PolicyRefusal
	{
		requireThat(askingThread, "askingThread").isNotNull();
		synchronized (this)
		{
			if (refusal != null)
				throw refusal;
			return askingThread.map(threads::get).orElse(session);
		}
	}
}
