# Shard Coverage

## Design Goals

- Work that is split into shards is executed completely and exactly once: no work item is left unexecuted, run by more
	than one shard, or failed without being named, even when a shard dies without reporting.

## Guidance

When you split work into shards or parallel parts, such as a partitioned test run, a matrix of jobs, or subagents that
each take part of a list of items, also add a coverage check that runs after every shard has finished, including one
that crashed or timed out. The check compares the full list of work items with what the shards report, and fails,
naming the items, when an item:

- was executed by no shard, for example because the partition dropped it or a shard died early;
- was executed by more than one shard;
- failed, including a shard that crashed or timed out without reporting its items.

Take the full list from an independent enumeration of the work, such as the test tool listing every test or the input
list, not from the shards' own counts, which cannot show an item that no shard knew about. An empty or unreadable shard
report counts as failed, never as zero items. Make the check a required step of the run, so that its failure fails the
run.
