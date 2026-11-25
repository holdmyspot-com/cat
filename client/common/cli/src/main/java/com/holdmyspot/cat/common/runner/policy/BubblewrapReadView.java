package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a bubblewrap read-only filesystem view with isolated process and network namespaces.
 */
public final class BubblewrapReadView
{
	/**
	 * Returns the read-only base view to which the caller adds its writable overlays.
	 *
	 * @param bubblewrap the protected native sandbox launcher
	 * @return a mutable command prefix
	 */
	public static List<String> readOnlyView(Path bubblewrap)
	{
		return new ArrayList<>(List.of(bubblewrap.toString(), "--die-with-parent", "--unshare-net", "--unshare-pid",
			"--ro-bind", "/", "/", "--proc", "/proc", "--dev", "/dev"));
	}

	/**
	 * Masks owner-denied reads after the writable overlays. An exception covering a denial leaves nothing to mask,
	 * and neither does a denied path that does not exist for the launching process, because the view shows only what
	 * that process sees. A partial exception or a path that cannot be masked makes the view unavailable.
	 *
	 * @param command   the sandbox command after its writable overlays
	 * @param policy    the authenticated owner's filesystem policy
	 * @param scratch   where private mask files can be created
	 * @param subject   the name of the client that reports a failure
	 * @param operation the operation that requires the sandbox
	 * @throws IOException if the view cannot enforce the read policy
	 */
	public static void applyReadDenials(List<String> command, ParentPolicy policy, Path scratch, String subject,
		String operation) throws IOException
	{
		applyReadDenials(command, policy, scratch, List.of(), subject, operation);
	}

	/**
	 * Masks owner-denied reads and any additional required masks after the writable overlays. Required masks apply
	 * even if the owner could read those paths. A path already covered by a mask needs no separate mount. An
	 * owner-denied path that does not exist for the launching process, such as a folder that its own sandbox hides,
	 * needs no mask, since the view shows the program only what that process sees; a required mask that does not exist
	 * makes the view unavailable.
	 *
	 * @param command       the sandbox command after its writable overlays
	 * @param policy        the authenticated owner's filesystem policy
	 * @param scratch       where private mask files can be created
	 * @param requiredMasks the absolute paths the view must always mask
	 * @param subject       the name of the client that reports a failure
	 * @param operation     the operation that requires the sandbox
	 * @throws IOException if the view cannot enforce the read policy or the required masks
	 */
	public static void applyReadDenials(List<String> command, ParentPolicy policy, Path scratch,
		List<Path> requiredMasks, String subject, String operation) throws IOException
	{
		if (!policy.sandboxed() && requiredMasks.isEmpty())
			return;
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
		List<Path> masked = new ArrayList<>();
		for (Path path : denied)
		{
			boolean mandatory = required.contains(path);
			if (RealPaths.isInsideAny(path, masked) || !mandatory && RealPaths.isInsideAny(path, exceptions))
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
			mask(command, path, mandatory, scratch, failure);
			masked.add(path);
		}
	}

	/**
	 * Replaces the denied filesystem object with an empty read-only object. A folder is replaced by an empty
	 * read-only folder, and any other object, such as a file or a socket, by an empty file. A path that does not exist
	 * for the launching process needs no mask unless it is required.
	 *
	 * @param command   the sandbox command
	 * @param denied    the canonical denied path
	 * @param mandatory {@code true} if the path is a required mask, which must exist
	 * @param scratch   where a private empty file can be created
	 * @param failure   the message if the denied object cannot be masked
	 * @throws IOException if the denied path cannot be masked
	 */
	private static void mask(List<String> command, Path denied, boolean mandatory, Path scratch, String failure)
		throws IOException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(denied, BasicFileAttributes.class);
		}
		catch (NoSuchFileException e)
		{
			if (mandatory)
				throw new IOException(failure, e);
			return;
		}
		catch (IOException e)
		{
			throw new IOException(failure, e);
		}
		if (attributes.isDirectory())
			command.addAll(List.of("--tmpfs", denied.toString(), "--remount-ro", denied.toString()));
		else
		{
			Path empty = Files.createTempFile(scratch, "denied-read-", ".empty",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("---------")));
			command.addAll(List.of("--ro-bind", empty.toString(), denied.toString()));
		}
	}

	/**
	 * Prevents construction.
	 */
	private BubblewrapReadView()
	{
	}
}
