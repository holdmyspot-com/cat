# Noticed synonym merged

## Variables

| Name | Description |
|---|---|
| synonym | Another name that the Javadoc uses for the documented retention period, such as `retention window` or `storage lifetime` |

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** A feedback service keeps each feedback report for a fixed number of days. Its API documentation calls that time the
**retention period**. The source code has this method:

```java
/**
 * Returns whether the report is past its {{synonym}}.
 *
 * @param now the current time
 * @return true if the report should be deleted
 */
public boolean isExpired(Instant now)
{
	return now.isAfter(createdAt.plus(retentionDays, ChronoUnit.DAYS));
}
```

Users report that a report created at 23:00 is deleted an hour early on the day it expires. The cause is not in this
method. Say what this method's Javadoc needs, if anything, and report anything else you notice in it.

**Request 2.** The same service's documentation uses **retention period** for how long a feedback report is kept and **grace
period** for how long a deleted report can still be restored. Write a one-sentence description of an API field that
returns how many days remain before a deleted report can no longer be restored.

## Assertions

### synonym_reported_and_merged

kind: effect

For request 1: The response reports that the Javadoc's **{{synonym}}** names the same concept as the documented
**retention period**, chooses one of the two terms, and says the other should be replaced.

### distinct_concepts_kept

kind: guard

For request 2: The description uses **grace period** for the restore window and does not merge it with the retention
period.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/consistent-terminology.md` before its
first governed action.
