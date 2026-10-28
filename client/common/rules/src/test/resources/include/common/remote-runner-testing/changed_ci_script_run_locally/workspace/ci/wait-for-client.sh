#!/usr/bin/env sh
# Part of the remote-session integration; does nothing unless REMOTE_SESSION_PUBLIC_KEY is set.
[ -n "${REMOTE_SESSION_PUBLIC_KEY:-}" ] || exit 0
echo "remote session step: wait-for-client"
