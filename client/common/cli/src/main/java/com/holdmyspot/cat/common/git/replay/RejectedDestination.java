package com.holdmyspot.cat.common.git.replay;

/**
 * An earlier candidate output that cannot receive a deletion because a concrete prerequisite is absent.
 *
 * @param output the rejected output
 * @param missingPrerequisite the prerequisite that makes the output incomplete
 */
record RejectedDestination(String output, String missingPrerequisite)
{
}
