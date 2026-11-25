package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes the paths that a confined child may not read, for every sandbox that confines one: the owner's denied
 * reads and the reads that CAT requires to be denied, without a path that another denial already covers.
 */
public final class ReadDenials
{
	/**
	 * A path that the child may not read.
	 *
	 * @param path      the canonical denied path
	 * @param mandatory {@code true} if the path is a required denial, which must exist, rather than one of the owner's
	 * @param failure   the message to report if the sandbox cannot enforce the denial
	 */
	public record Denial(Path path, boolean mandatory, String failure)
	{
	}

	/**
	 * Returns the denials that a sandbox must enforce, shallowest path first. A path inside another denial needs no
	 * denial of its own, and neither does an owner-denied path inside a re-allowance, but a required denial applies even
	 * where the owner may read the path. A partial exception or a path that no sandbox can deny makes the denials
	 * unrepresentable.
	 *
	 * @param policy        the authenticated owner's filesystem policy
	 * @param requiredMasks the absolute paths that must always be denied
	 * @param subject       the name of the client that reports a failure
	 * @param operation     the operation that requires the sandbox
	 * @return the denials, possibly none
	 * @throws IOException if the sandbox cannot enforce the read policy or the required denials
	 */
	public static List<Denial> of(ParentPolicy policy, List<Path> requiredMasks, String subject, String operation)
		throws IOException
	{
		if (!policy.sandboxed() && requiredMasks.isEmpty())
			return List.of();
		List<Path> required = RealPaths.resolveAll(requiredMasks);
		List<Path> exceptions = List.of();
		List<Path> denied = new ArrayList<>(required);
		if (policy.sandboxed())
		{
			exceptions = RealPaths.resolveAll(policy.readReallowances()).stream().
				filter(path -> !RealPaths.isInsideAny(path, required)).toList();
			denied.addAll(RealPaths.resolveAll(policy.readDenials()));
		}
		denied.sort(Comparator.comparingInt(Path::getNameCount));
		List<Path> covered = new ArrayList<>();
		List<Denial> denials = new ArrayList<>();
		for (Path path : denied)
		{
			boolean mandatory = required.contains(path);
			if (RealPaths.isInsideAny(path, covered) || !mandatory && RealPaths.isInsideAny(path, exceptions))
				continue;
			String denial = "the owner's denied read";
			if (mandatory)
				denial = "a required denied read";
			String failure = subject + " cannot mask " + denial + " during " + operation + ": " + path;
			if (path.getParent() == null || path.startsWith("/proc") || path.startsWith("/dev") ||
				!mandatory && exceptions.stream().anyMatch(exception -> exception.startsWith(path)))
			{
				if (mandatory)
					throw new IOException(failure);
				throw new IOException(subject + " cannot represent the owner's read restriction during " + operation +
					": " + path);
			}
			denials.add(new Denial(path, mandatory, failure));
			covered.add(path);
		}
		return denials;
	}

	/**
	 * Prevents construction.
	 */
	private ReadDenials()
	{
	}
}
