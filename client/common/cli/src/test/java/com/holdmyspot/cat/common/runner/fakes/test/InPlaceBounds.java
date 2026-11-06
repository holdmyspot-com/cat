package com.holdmyspot.cat.common.runner.fakes.test;

import com.holdmyspot.cat.common.runner.policy.InPlaceBound;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.ParentPolicySource;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Bounds of a session that starts in place, for runner tests whose launch must not, or cannot, read the policy of
 * the session whose command runs the runner.
 */
public final class InPlaceBounds
{
	/**
	 * Returns a bound that fails the test if a launch asks it for a bound, for a launch that names
	 * {@code --policy-bound} or is refused before it is bounded.
	 *
	 * @return the bound
	 */
	public static InPlaceBound unused()
	{
		return new InPlaceBound(new ParentPolicySource()
		{
			@Override
			public String harnessName()
			{
				throw new AssertionError("The launch read the policy of the session that runs it");
			}

			@Override
			public String permissionEntries(List<Path> writes, Set<String> hosts)
			{
				throw new AssertionError("The launch read the policy of the session that runs it");
			}

			@Override
			public ParentPolicy read()
			{
				throw new AssertionError("The launch read the policy of the session that runs it");
			}
		}, (_, _, _, _) ->
		{
			throw new AssertionError("The launch asked what its session needs");
		});
	}

	/**
	 * Returns a bound whose parent policy cannot be read.
	 *
	 * @param harnessName the name of the parent's harness
	 * @param refusal     why the policy cannot be read
	 * @return the bound
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static InPlaceBound refusing(String harnessName, PolicyRefusal refusal)
	{
		requireThat(harnessName, "harnessName").isNotNull();
		requireThat(refusal, "refusal").isNotNull();
		return new InPlaceBound(new ParentPolicySource()
		{
			@Override
			public String harnessName()
			{
				return harnessName;
			}

			@Override
			public String permissionEntries(List<Path> writes, Set<String> hosts)
			{
				throw new AssertionError("The launch asked for the settings of a missing permission");
			}

			@Override
			public ParentPolicy read() throws PolicyRefusal
			{
				throw refusal;
			}
		}, (_, _, _, _) ->
		{
			throw new AssertionError("The launch asked what its session needs");
		});
	}

	/**
	 * Prevents construction.
	 */
	private InPlaceBounds()
	{
	}
}
