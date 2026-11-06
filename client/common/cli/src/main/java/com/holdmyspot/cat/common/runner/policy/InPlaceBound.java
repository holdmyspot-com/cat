package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Computes the policy that bounds a session that a runner starts in place, inside the sandbox of the harness session
 * whose command runs the runner. The bound gives the child what it needs and no more than the parent's policy allows;
 * a launch whose child needs more is refused with the settings that would allow it.
 * <p>
 * The child is a descendant of the parent's command, so the parent's sandbox confines it whatever the bound says. The
 * bound decides whether the launch is refused before the child starts, and it bounds the sandbox that the child's
 * harness applies to the child's commands.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if its sources are.
 */
public final class InPlaceBound
{
	private final ParentPolicySource parent;
	private final ChildNeedsSource needs;

	/**
	 * Creates a bound.
	 *
	 * @param parent reads the policy of the session whose command runs the runner
	 * @param needs  says what the child needs
	 * @throws NullPointerException if any of the arguments are null
	 */
	public InPlaceBound(ParentPolicySource parent, ChildNeedsSource needs)
	{
		requireThat(parent, "parent").isNotNull();
		requireThat(needs, "needs").isNotNull();
		this.parent = parent;
		this.needs = needs;
	}

	/**
	 * Returns the policy that bounds a child.
	 *
	 * @param harness     the child's harness identifier
	 * @param runRoot     the folder that holds the child's run
	 * @param deniedReads the absolute paths that the launch denies the child
	 * @param extra       the writes and hosts that the child's commands need beyond what its harness needs
	 * @return the bound
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 * @throws SessionRefusedException  if the parent's policy cannot be read safely, or does not allow what the child
	 *                                  needs; the message names the settings that would allow it
	 * @throws IOException              if what the child needs cannot be determined
	 */
	public ChildPolicy bound(String harness, Path runRoot, List<Path> deniedReads, ExtraPermissions extra)
		throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(extra, "extra").isNotNull();
		ParentPolicy policy;
		try
		{
			policy = parent.read();
		}
		catch (PolicyRefusal e)
		{
			throw new SessionRefusedException(e.messageFor(parent.harnessName()), e);
		}
		catch (SessionRefusedException e)
		{
			throw e;
		}
		catch (IOException e)
		{
			throw new SessionRefusedException("CAT cannot start the session: it cannot read this " +
				parent.harnessName() + " session's sandbox policy: " + e.getMessage(), e);
		}
		try
		{
			return ChildPolicy.intersect(policy, needs.needs(harness, runRoot, deniedReads, policy).with(extra));
		}
		catch (PolicyRefusal e)
		{
			throw new SessionRefusedException(e.messageFor(parent.harnessName()), e);
		}
		catch (MissingPermissionsException e)
		{
			String entries = "";
			if (!e.writes().isEmpty() || !e.hosts().isEmpty())
				entries = parent.permissionEntries(e.writes(), e.hosts());
			throw new SessionRefusedException(e.messageFor(parent.harnessName(), entries), e);
		}
	}
}
