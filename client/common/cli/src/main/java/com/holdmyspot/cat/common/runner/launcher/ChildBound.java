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
	private final ChildNeedsSource needs;
	/**
	 * The policy that every reading of the parent's is narrowed to: the snapshots' readings combined, or
	 * {@link ParentPolicy#UNSANDBOXED}, which narrows nothing, before the first snapshot.
	 */
	private final ParentPolicy snapshot;
	/**
	 * The absolute paths that a reading of the parent's policy must keep the parent's commands from writing.
	 */
	private final List<Path> trusted;

	/**
	 * Creates a bound that reads the parent's policy for each launch.
	 *
	 * @param parent reads the policy of the parent session
	 * @param needs  says what the child needs
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ChildBound(ParentPolicySource parent, ChildNeedsSource needs)
	{
		this(parent, needs, ParentPolicy.UNSANDBOXED, List.of());
	}

	/**
	 * Creates a bound.
	 *
	 * @param parent   reads the policy of the parent session
	 * @param needs    says what the child needs
	 * @param snapshot the policy that every reading of the parent's is narrowed to
	 * @param trusted  the absolute paths that a reading of the parent's policy must keep the parent's commands from
	 *                 writing
	 * @throws NullPointerException if any of the arguments are null
	 */
	private ChildBound(ParentPolicySource parent, ChildNeedsSource needs, ParentPolicy snapshot, List<Path> trusted)
	{
		requireThat(parent, "parent").isNotNull();
		requireThat(needs, "needs").isNotNull();
		requireThat(snapshot, "snapshot").isNotNull();
		requireThat(trusted, "trusted").isNotNull();
		this.parent = parent;
		this.needs = needs;
		this.snapshot = snapshot;
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
		return new ChildBound(parent, needs, readParent(snapshot, allTrusted, Optional.empty()), allTrusted);
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
		return readParent(snapshot, trusted, askingThread);
	}

	/**
	 * Returns the policy that bounds a child.
	 *
	 * @param harness      the child's harness identifier
	 * @param runRoot      the folder that holds the child's run
	 * @param deniedReads  the absolute paths that the launch denies the child
	 * @param extra        the writes and hosts that the child's commands need beyond what its harness needs
	 * @param askingThread the identifier that the parent's harness gives the thread that asks for the child, or
	 *                     empty for the parent's main thread
	 * @return the bound
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 * @throws SessionRefusedException  if the parent's policy cannot be read safely, or does not allow what the child
	 *                                  needs; the message names the settings that would allow it
	 * @throws IOException              if what the child needs cannot be determined
	 */
	public ChildPolicy bound(String harness, Path runRoot, List<Path> deniedReads, ExtraPermissions extra,
		Optional<String> askingThread) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		requireThat(runRoot, "runRoot").isNotNull();
		requireThat(deniedReads, "deniedReads").isNotNull();
		requireThat(extra, "extra").isNotNull();
		requireThat(askingThread, "askingThread").isNotNull();
		ParentPolicy policy = readParent(snapshot, trusted, askingThread);
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
	 * Reads the parent's policy, checks that it keeps the parent's commands from writing some paths, and narrows it to
	 * a snapshot.
	 *
	 * @param narrowedTo   the policy that the reading is narrowed to
	 * @param trustedPaths the absolute paths that the reading must keep the parent's commands from writing
	 * @param askingThread the thread whose policy is read, or empty for the parent's main thread
	 * @return the policy
	 * @throws SessionRefusedException if the policy cannot be read safely
	 */
	private ParentPolicy readParent(ParentPolicy narrowedTo, List<Path> trustedPaths, Optional<String> askingThread)
		throws SessionRefusedException
	{
		try
		{
			ParentPolicy policy = parent.read(askingThread);
			for (Path path : trustedPaths)
				policy.requireTrusted(path);
			return narrowedTo.intersect(policy);
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
}
