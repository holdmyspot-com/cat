package com.holdmyspot.cat.common.runner.launcher;

import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Says what a requested child session's commands need, which depends on the child's harness: the network host of its
 * model, the files of its harness login that its commands must not read, and anything else that its harness needs.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
@FunctionalInterface
public interface ChildNeedsSource
{
	/**
	 * Returns a child's needs.
	 *
	 * @param harness     the child's harness identifier
	 * @param runFolder   the folder that holds the child's run
	 * @param deniedReads the absolute paths that the request denies the child
	 * @param bound       the requesting session's policy, which bounds the child
	 * @return the child's needs
	 * @throws IOException   if the needs cannot be determined
	 * @throws PolicyRefusal if the child cannot run safely within {@code bound}, such as when {@code bound} keeps the
	 *                       parent from reading a login file that the child's isolated home would copy, so that the
	 *                       copy would give the parent what its policy denies it
	 */
	ChildNeeds needs(String harness, Path runFolder, List<Path> deniedReads, ParentPolicy bound)
		throws IOException, PolicyRefusal;
}
