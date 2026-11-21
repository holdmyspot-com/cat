package com.holdmyspot.cat.common.runner.launcher;

import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.MissingPermissionsException;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Computes the policy that bounds a child session: the child gets what it needs and no more than the policy of its
 * parent, the harness session that asks for it, allows. A launch whose child needs more is refused with the settings
 * that would allow it.
 * <p>
 * A bound reads the parent's policy for each launch. A {@link #snapshot(Path) snapshot} also bounds every later launch
 * by the policy that the parent had when the snapshot was taken, so that a change of the parent's settings after it
 * can narrow a child but never widen it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable; it is thread-safe if its sources are.
 */
public final class ChildBound
{
	private final ParentPolicySource parent;
	/**
	 * The original policy readings, kept so trust checks can detect a write grant from any snapshot.
	 */
	private final List<ParentPolicy> snapshots;
	/**
	 * The absolute paths that a reading of the parent's policy must keep the parent's commands from writing.
	 */
	private final List<Path> trusted;

	/**
	 * Creates a bound that reads the parent's policy for each launch.
	 *
	 * @param parent reads the policy of the parent session
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ChildBound(ParentPolicySource parent)
	{
		this(parent, List.of(), List.of());
	}

	/**
	 * Creates a bound.
	 *
	 * @param parent   reads the policy of the parent session
	 * @param snapshots the original policy readings
	 * @param trusted  the absolute paths that a reading of the parent's policy must keep the parent's commands from
	 *                 writing
	 * @throws NullPointerException if any of the arguments are null
	 */
	private ChildBound(ParentPolicySource parent, List<ParentPolicy> snapshots, List<Path> trusted)
	{
		requireThat(parent, "parent").isNotNull();
		requireThat(trusted, "trusted").isNotNull();
		this.parent = parent;
		this.snapshots = List.copyOf(snapshots);
		this.trusted = List.copyOf(trusted);
	}

	/**
	 * Reads the parent's policy now and returns a bound that this reading narrows. The returned bound refuses every
	 * reading, this one included, that lets the parent's commands write {@code trusted}: CAT reads that path outside
	 * the parent's sandbox, and the commands could otherwise change what CAT reads there to widen a child.
	 *
	 * @param trusted the absolute path of a file or folder that the parent's commands must not be able to write
	 * @return the bound
	 * @throws NullPointerException     if {@code trusted} is null
	 * @throws IllegalArgumentException if {@code trusted} is not absolute
	 * @throws SessionRefusedException  if the parent's policy cannot be read safely; the message says how to fix it
	 */
	public ChildBound snapshot(Path trusted) throws SessionRefusedException
	{
		requireThat(trusted, "trusted").isNotNull();
		if (!trusted.isAbsolute())
			throw new IllegalArgumentException("trusted must be absolute: " + trusted);
		List<Path> allTrusted = new ArrayList<>(this.trusted);
		allTrusted.add(trusted);
		return new ChildBound(parent, readPolicies(allTrusted, Optional.empty()), allTrusted);
	}

	/**
	 * Reads the parent's policy now, as {@link #bound} reads it: narrowed to the snapshots, and refused if it lets the
	 * parent's commands write a trusted path.
	 *
	 * @param askingThread the identifier that the parent's harness gives the thread that asks for the child, or
	 *                     empty for the parent's main thread
	 * @return the policy
	 * @throws NullPointerException    if {@code askingThread} is null
	 * @throws SessionRefusedException if the parent's policy cannot be read safely; the message says how to fix it
	 */
	public ParentPolicy parentPolicy(Optional<String> askingThread) throws SessionRefusedException
	{
		requireThat(askingThread, "askingThread").isNotNull();
		return intersect(readPolicies(trusted, askingThread));
	}

	/**
	 * Returns the narrowed owner policy after verifying a source against each authenticated reading separately.
	 *
	 * @param askingThread the requesting thread, or empty for the parent's main thread
	 * @param check verifies trust before the policy can be used outside the owner's sandbox
	 * @return the narrowed policy
	 * @throws IOException if trust or the owner policy cannot be established
	 */
	public ParentPolicy parentPolicy(Optional<String> askingThread, PolicyCheck check) throws IOException
	{
		requireThat(askingThread, "askingThread").isNotNull();
		requireThat(check, "check").isNotNull();
		List<ParentPolicy> policies = readPolicies(trusted, askingThread);
		try
		{
			check.requireTrusted(policies);
		}
		catch (PolicyRefusal e)
		{
			throw new SessionRefusedException(e.messageFor(parent.harnessName()), e);
		}
		return intersect(policies);
	}

	/**
	 * Returns the policy that bounds a child.
	 *
	 * @param harness       the child's harness identifier
	 * @param resultsFolder the folder that holds the child's nested session
	 * @param deniedReads   the absolute paths that the launch denies the child
	 * @param extra         the writes and hosts that the child's commands need beyond what its harness needs
	 * @param askingThread  the identifier that the parent's harness gives the thread that asks for the child, or
	 *                      empty for the parent's main thread
	 * @param needs         says what the child needs
	 * @return the bound
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 * @throws SessionRefusedException  if the parent's policy cannot be read safely, or does not allow what the child
	 *                                  needs; the message names the settings that would allow it
	 * @throws IOException              if what the child needs cannot be determined
	 */
	public ChildPolicy bound(String harness, Path resultsFolder, List<Path> deniedReads, ExtraPermissions extra,
		Optional<String> askingThread, ChildNeedsSource needs) throws IOException
	{
		return bound(harness, resultsFolder, deniedReads, extra, askingThread, needs, _ ->
		{
			// This needs source does not execute an installed peer.
		});
	}

	/**
	 * Bounds the child after checking every observed owner policy against the source of its needs.
	 *
	 * @param harness the child's harness identifier
	 * @param resultsFolder the folder that holds the child's nested session
	 * @param deniedReads the absolute paths the launch denies the child
	 * @param extra the writes and hosts the child's commands need beyond what its harness needs
	 * @param askingThread the requesting thread, or empty for the parent's main thread
	 * @param needs says what the child needs
	 * @param check verifies trust before reading the child's needs
	 * @return the bound
	 * @throws IOException if trust or needs cannot be determined, or if the parent refuses the launch
	 */
	public ChildPolicy bound(String harness, Path resultsFolder, List<Path> deniedReads, ExtraPermissions extra,
		Optional<String> askingThread, ChildNeedsSource needs, PolicyCheck check) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(resultsFolder, "resultsFolder").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(extra, "extra").isNotNull();
		requireThat(askingThread, "askingThread").isNotNull();
		requireThat(needs, "needs").isNotNull();
		requireThat(check, "check").isNotNull();
		List<ParentPolicy> policies = readPolicies(trusted, askingThread);
		try
		{
			check.requireTrusted(policies);
			ParentPolicy policy = intersect(policies);
			return ChildPolicy.intersect(policy, needs.needs(harness, resultsFolder, deniedReads, policy).with(extra));
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

	/**
	 * Returns the reason for refusing a launch whose parent's policy cannot be read.
	 *
	 * @param harnessName the name of the parent's harness as its user knows it
	 * @param failure     the failure to read the policy
	 * @return the reason
	 */
	public static String unreadablePolicy(String harnessName, IOException failure)
	{
		return "CAT cannot start the session: it cannot read this " + harnessName + " session's sandbox policy: " +
			failure.getMessage();
	}

	/**
	 * Reads the parent's policy, checks the paths it must protect and appends the reading to the existing snapshots.
	 *
	 * @param trustedPaths the absolute paths that the reading must keep the parent's commands from writing
	 * @param askingThread the thread whose policy is read, or empty for the parent's main thread
	 * @return the snapshot readings and the current reading
	 * @throws SessionRefusedException if the policy cannot be read safely
	 */
	private List<ParentPolicy> readPolicies(List<Path> trustedPaths, Optional<String> askingThread)
		throws SessionRefusedException
	{
		try
		{
			List<ParentPolicy> current = parent.readings(askingThread);
			if (current.isEmpty())
				throw new IOException("No authenticated owner policy was available");
			List<ParentPolicy> policies = new ArrayList<>(snapshots);
			for (ParentPolicy policy : current)
				policies.add(ParentPolicy.UNSANDBOXED.intersect(policy));
			for (ParentPolicy policy : policies)
			{
				for (Path path : trustedPaths)
					policy.requireTrusted(path);
			}
			return List.copyOf(policies);
		}
		catch (PolicyRefusal e)
		{
			throw new SessionRefusedException(e.messageFor(parent.harnessName()), e);
		}
		catch (IOException e)
		{
			throw new SessionRefusedException(unreadablePolicy(parent.harnessName(), e), e);
		}
	}

	/**
	 * Returns the permissions common to all readings of the parent's policy.
	 *
	 * @param policies the individual readings
	 * @return the narrowed policy
	 * @throws SessionRefusedException if a path cannot be resolved
	 */
	private ParentPolicy intersect(List<ParentPolicy> policies) throws SessionRefusedException
	{
		try
		{
			ParentPolicy result = ParentPolicy.UNSANDBOXED;
			for (ParentPolicy policy : policies)
				result = result.intersect(policy);
			return result;
		}
		catch (IOException e)
		{
			throw new SessionRefusedException(unreadablePolicy(parent.harnessName(), e), e);
		}
	}

	/**
	 * Validates a source before it runs outside the parent's sandbox.
	 */
	@FunctionalInterface
	public interface PolicyCheck
	{
		/**
		 * Checks the source against every observed parent policy, including the current reading.
		 *
		 * @param policies the individual readings, before intersection
		 * @throws IOException if the source's files cannot be determined
		 * @throws PolicyRefusal if the source is writable under a sandbox policy
		 */
		void requireTrusted(List<ParentPolicy> policies) throws IOException, PolicyRefusal;
	}
}
