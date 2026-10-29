# Worker discovery beside report storage

## Design Goals

- Prove that changing how a report queue finds the workers it delivers to leaves the code that stores pending and
	delivered reports unchanged, and puts the new way of finding workers in a class of its own.

## Variables

- **worker_suffix**: The file name extension that marks a worker's announcement file, without its dot, such as
	`worker` or `endpoint`.

## Scenario

`ReportQueue` delivers reports to a fixed list of workers that it receives when it is created. Workers now come and go:
each running worker announces itself by writing a file named `<worker name>.{{worker_suffix}}` into a shared
directory, and deletes it when it stops. The file's only line is the worker's address, such as
`http://10.0.0.7:8080/reports`. Other files in that directory are not workers.

Change `ReportQueue` so that each delivery sends the pending reports to the workers announced in that directory at that
time, instead of the fixed list.

## Assertions

### discovery_in_focused_class

- **kind**: effect
- **description**: The code that lists the shared directory, selects the `.{{worker_suffix}}` files and reads each
	worker's address is in a class other than `ReportQueue`, which `ReportQueue` uses to obtain the workers for each
	delivery.

### storage_unchanged

- **kind**: effect
- **description**: The diff leaves `ReportQueue.submit`, `ReportQueue.pendingReports` and `ReportQueue.markDelivered`
	unchanged, including the `.pending` and `.delivered` file names and the atomic move.

### discovery_works

- **kind**: guard
- **description**: Each call of `deliverPending` finds the workers again from the shared directory, treats only files
	whose names end in `.{{worker_suffix}}` as workers, takes each worker's address from its file's line, and sends
	every pending report to each of those workers before marking it delivered. The fixed worker list is no longer used.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/class-design.md` before
	its first governed action.
