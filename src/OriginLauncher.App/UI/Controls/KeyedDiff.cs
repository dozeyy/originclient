using System.Collections.ObjectModel;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// Brings a bound list in line with a freshly built one using real Insert / Remove /
/// Move / Replace, matched by a stable key. Only rows that are new, gone, displaced
/// or whose signature changed are touched, so an insert near the top no longer rebuilds
/// every row after it (which dropped hover and keyboard focus), a row that jumps ten
/// places is ONE move, and a capped chat that trims its oldest lines and appends new
/// ones costs just those removes and adds.
///
/// How: rows present in both lists keep their relative order when they are already
/// in order (the longest increasing run of their old positions stays put); every
/// other row is moved, or inserted, to sit right after its predecessor in the new list.
/// Keys are cached once per call. Repeated keys are tolerated (the n-th "k" matches
/// the n-th "k"). Pure logic: no WPF types, unit-tested (KeyedDiffTests).
/// </summary>
public static class KeyedDiff
{
    /// <summary>True when the list changed at all.</summary>
    public static bool Apply<T>(
        ObservableCollection<T> target, IReadOnlyList<T> next, Func<T, string> key, Func<T, string> signature)
    {
        var changed = false;
        var wanted = Identify(next.Select(key));
        var cur = Identify(target.Select(key));
        var wantedSet = new HashSet<string>(wanted);

        // 1. Drop what is gone (back to front, so indexes stay valid).
        for (var i = cur.Count - 1; i >= 0; i--)
        {
            if (wantedSet.Contains(cur[i])) continue;
            target.RemoveAt(i);
            cur.RemoveAt(i);
            changed = true;
        }

        // 2. Which surviving rows are already in the right relative order? They stay.
        var oldIndex = new Dictionary<string, int>(cur.Count);
        for (var i = 0; i < cur.Count; i++) oldIndex[cur[i]] = i;
        var present = new List<(int NextIndex, int OldIndex)>();
        for (var i = 0; i < wanted.Count; i++)
        {
            if (oldIndex.TryGetValue(wanted[i], out var old)) present.Add((i, old));
        }
        var stays = LongestIncreasing(present);

        // 3. Walk the new order: insert what is new, and move what is out of place, right after its predecessor.
        for (var i = 0; i < wanted.Count; i++)
        {
            var id = wanted[i];
            var isNew = !oldIndex.ContainsKey(id);
            if (!isNew && stays.Contains(i)) continue;

            var predecessor = i == 0 ? -1 : IndexOf(cur, wanted[i - 1]);
            if (isNew)
            {
                var at = predecessor + 1;
                target.Insert(at, next[i]);
                cur.Insert(at, id);
                changed = true;
                continue;
            }

            var from = IndexOf(cur, id);
            // Moving removes the row first, so a row coming from before its predecessor lands one place earlier.
            var to = from > predecessor ? predecessor + 1 : predecessor;
            if (from == to) continue;
            target.Move(from, to);
            cur.RemoveAt(from);
            cur.Insert(to, id);
            changed = true;
        }

        // 4. Same order now: replace any row whose content changed.
        for (var i = 0; i < next.Count; i++)
        {
            if (ReferenceEquals(target[i], next[i]) || signature(target[i]) == signature(next[i])) continue;
            target[i] = next[i];
            changed = true;
        }
        return changed;
    }

    /// <summary>Makes repeated keys unique ("k", "k" becomes "k", "k#1") so the n-th matches the n-th.</summary>
    private static List<string> Identify(IEnumerable<string> keys)
    {
        var seen = new Dictionary<string, int>();
        var ids = new List<string>();
        foreach (var k in keys)
        {
            seen.TryGetValue(k, out var count);
            seen[k] = count + 1;
            ids.Add(count == 0 ? k : $"{k}#{count}");
        }
        return ids;
    }

    // The last row is the common case (appending a new message), so check it before scanning.
    private static int IndexOf(List<string> ids, string id) =>
        ids.Count > 0 && ids[^1] == id ? ids.Count - 1 : ids.IndexOf(id);

    /// <summary>The new-list positions of the longest run of rows whose old positions also increase (patience sort, n log n).</summary>
    private static HashSet<int> LongestIncreasing(List<(int NextIndex, int OldIndex)> items)
    {
        var tails = new List<int>();           // item index ending the best run of each length
        var previous = new int[items.Count];
        for (var i = 0; i < items.Count; i++)
        {
            int lo = 0, hi = tails.Count;
            while (lo < hi)
            {
                var mid = (lo + hi) / 2;
                if (items[tails[mid]].OldIndex < items[i].OldIndex) lo = mid + 1;
                else hi = mid;
            }
            previous[i] = lo > 0 ? tails[lo - 1] : -1;
            if (lo == tails.Count) tails.Add(i);
            else tails[lo] = i;
        }

        var result = new HashSet<int>();
        for (var at = tails.Count > 0 ? tails[^1] : -1; at >= 0; at = previous[at]) result.Add(items[at].NextIndex);
        return result;
    }
}
