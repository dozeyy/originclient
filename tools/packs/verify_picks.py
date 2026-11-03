"""Placeholder until the picks list lands (replaced in the same branch)."""
import os
import sys

if not os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "picks.json")):
    print("no picks.json yet")
    sys.exit(0)
