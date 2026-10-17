# Document Export Product Contract

An authorized user may export selected documents. The service must reject an export if {{rejection_condition}}. A completed export and accurate result must survive a process restart, including a crash after completion but
before the caller first observes it.

Acceptance requires an authorized-export check, a check that rejects an export if {{rejection_condition}}, and a fault-and-restart check after
completion but before the caller first observes it.
