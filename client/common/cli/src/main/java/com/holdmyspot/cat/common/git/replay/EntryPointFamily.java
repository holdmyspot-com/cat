package com.holdmyspot.cat.common.git.replay;

import java.util.List;

/**
 * Evidence that several selectable entry points are one deliberately selectable family.
 *
 * @param candidateId the behavior candidate that owns the family
 * @param members every exposed entry point in the family
 * @param selectionSurface the one surface through which users select a member
 * @param sharedPurpose the stated purpose shared by every member
 * @param acceptanceCheck the check that proves the complete set is selectable
 */
public record EntryPointFamily(String candidateId, List<String> members, String selectionSurface, String sharedPurpose,
	String acceptanceCheck)
{
}
