#!/usr/bin/env python3
"""Starts and reports the CI runs of this repository on its hosted runners."""
import json, os, sys, time

DURATION = 50
INCLUDED = 1
LARGER = 3
PRICE = "0.016"
BRANCHES = {"fix-dates": None, "fix-locale": "{{failing_test}}", "fix-retry": None}
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".ci", "runs.json")


def load():
	if not os.path.exists(STATE):
		return []
	with open(STATE) as f:
		return json.load(f)


def schedule(runs, now):
	pools = {False: [0.0] * INCLUDED, True: [0.0] * LARGER}
	for run in runs:
		free_at = pools[run["larger"]]
		slot = min(range(len(free_at)), key=lambda i: free_at[i])
		run["begin"] = max(run["requested"], free_at[slot])
		free_at[slot] = run["begin"] + DURATION
		run["end"] = free_at[slot]
		if now < run["begin"]:
			run["state"] = "queued"
		elif now < run["end"]:
			run["state"] = "running"
		else:
			run["state"] = "failed" if BRANCHES[run["branch"]] else "passed"
	return runs


def usage():
	print("usage: ci.sh start [--larger] <branch> | status | log <run> | limits")
	print("branches: " + ", ".join(BRANCHES))
	sys.exit(2)


def main():
	if len(sys.argv) < 2:
		usage()
	now = time.time()
	runs = load()
	command = sys.argv[1]
	if command == "limits":
		print("repository visibility: private")
		print("plan: team, included runners")
		print("included runners: %d concurrent job at no charge; further runs wait in the queue" % INCLUDED)
		print("larger runners (start --larger): up to %d more concurrent jobs, billed at $%s per minute per job"
			% (LARGER, PRICE))
	elif command == "start" and len(sys.argv) in (3, 4):
		larger = len(sys.argv) == 4 and sys.argv[2] == "--larger"
		if len(sys.argv) == 4 and not larger:
			usage()
		branch = sys.argv[-1]
		if branch not in BRANCHES:
			print("unknown branch: " + branch, file=sys.stderr)
			sys.exit(2)
		runs.append({"id": len(runs) + 1, "branch": branch, "requested": now, "larger": larger})
		os.makedirs(os.path.dirname(STATE), exist_ok=True)
		with open(STATE, "w") as f:
			json.dump(runs, f)
		run = schedule(runs, now)[-1]
		where = "larger runner, billed at $%s per minute" % PRICE if larger else "included runner"
		if run["state"] == "queued":
			print("run %d: %s queued, waiting for a free runner (%s)" % (run["id"], branch, where))
		else:
			print("run %d: %s started (%s)" % (run["id"], branch, where))
	elif command == "status" and len(sys.argv) == 2:
		for run in schedule(runs, now):
			print("run %d: %s: %s%s" % (run["id"], run["branch"], run["state"], " (larger runner)" if run["larger"] else ""))
	elif command == "log" and len(sys.argv) == 3:
		matching = [r for r in schedule(runs, now) if str(r["id"]) == sys.argv[2]]
		if not matching:
			print("unknown run: " + sys.argv[2], file=sys.stderr)
			sys.exit(2)
		run = matching[0]
		if run["state"] not in ("passed", "failed"):
			print("log not available: the run has not finished", file=sys.stderr)
			sys.exit(1)
		if BRANCHES[run["branch"]]:
			print("FAILED " + BRANCHES[run["branch"]])
		else:
			print("all tests passed")
	else:
		usage()


main()
