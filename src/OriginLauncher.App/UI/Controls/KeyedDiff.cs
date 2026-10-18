using System.Collections.ObjectModel;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// Brings a bound list in line with a freshly built one using real Insert / Remove /
/// Move, matched by a stable key. Only rows whose key is new, gone, moved or whose
/// signature changed are touched, so an insert near the top no longer rebuilds every
/// row after it (which dropped hover and keyboard focus), and a capped chat that
/// trims its oldest line and appends a new one costs one remove and one add.
/// Pure logic: no WPF types, unit-tested (KeyedDiffTests).
/// </summary>
public static class KeyedDiff
{
    /// <summary>True when the list changed at all. Keys must be unique within <paramref name="next"/>.</summary>
    public static bool Apply<T>(
        ObservableCollection<T> target, IReadOnlyList<T> next, Func<T, string> key, Func<T, string> signature)
    {
        var changed = false;

        // 1. Drop what is gone (back to front, so indexes stay valid).
        var wanted = new HashSet<string>(next.Select(key));
        for (var i = target.Count - 1; i >= 0; i--)
        {
            if (wanted.Contains(key(target[i]))) continue;
            target.RemoveAt(i);
            changed = true;
        }

        // 2. Walk the new order: keep, move or insert.
        for (var i = 0; i < next.Count; i++)
        {
            var item = next[i];
            var k = key(item);
            if (i < target.Count && key(target[i]) == k)
            {
                if (signature(target[i]) != signature(item))
                {
                    target[i] = item;
                    changed = true;
                }
                continue;
            }

            var from = -1;
            for (var j = i + 1; j < target.Count; j++)
            {
                if (key(target[j]) != k) continue;
                from = j;
                break;
            }
            if (from >= 0)
            {
                target.Move(from, i);
                if (signature(target[i]) != signature(item)) target[i] = item;
            }
            else
            {
                target.Insert(i, item);
            }
            changed = true;
        }

        // 3. Anything left over at the end (only when keys repeat) goes.
        while (target.Count > next.Count)
        {
            target.RemoveAt(target.Count - 1);
            changed = true;
        }
        return changed;
    }
}
