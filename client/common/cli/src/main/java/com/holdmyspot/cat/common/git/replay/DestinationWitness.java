package com.holdmyspot.cat.common.git.replay;

/**
 * The exact staged-tree transition expected when one batch is applied.
 *
 * @param beforeTree the full Git tree ID required before the batch is applied
 * @param afterTree the full Git tree ID required after the batch is applied
 */
public record DestinationWitness(String beforeTree, String afterTree)
{
}
