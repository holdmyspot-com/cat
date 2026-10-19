package com.holdmyspot.cat.common.git.replay;

/**
 * An earlier output that cannot contain a behavior candidate.
 *
 * @param output the earlier output identifier
 * @param reason the missing condition that prevents placement there
 */
public record RejectedEarlierOutput(String output, String reason)
{
}
