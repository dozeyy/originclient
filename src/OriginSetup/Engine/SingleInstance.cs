using System.Runtime.InteropServices;
using System.Threading;

namespace OriginSetup.Engine;

// One Setup (or uninstall) at a time in this Windows session. Two at once share the install folder's
// _setup-staging: the second one's Stage deletes the first's half-finished download, and both can move files into
// place. A player double-clicking Setup twice (the first window takes a second to appear) did exactly that. The
// second copy now hands over to the first: it asks it to come to the front and exits.
public sealed class SingleInstance : IDisposable
{
    public const string DefaultName = @"Local\OriginSetup.Running";

    private readonly Mutex _mutex;
    private readonly EventWaitHandle _show;
    private RegisteredWaitHandle? _wait;
    private bool _owned;

    private SingleInstance(string name)
    {
        _mutex = new Mutex(false, name);
        try { _show = new EventWaitHandle(false, EventResetMode.AutoReset, name + ".Show"); }
        catch
        {
            _mutex.Dispose();
            throw;
        }
    }

    /// <summary>Null when another Setup in this session already runs (it has been asked to come forward).</summary>
    /// <param name="wait">How long to wait for the other one to finish (silent installs wait; windows don't).</param>
    public static SingleInstance? TryClaim(TimeSpan wait, string name = DefaultName)
    {
        SingleInstance instance;
        try { instance = new SingleInstance(name); }
        catch (UnauthorizedAccessException)
        {
            // The running Setup is elevated and this copy isn't: Windows keeps its mutex out of reach, which itself
            // says one is running. Hand over the same way (this used to reach the crash net's "has to close" box).
            AllowForeground();
            try
            {
                using var show = EventWaitHandle.OpenExisting(name + ".Show");
                show.Set();
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException || ex is WaitHandleCannotBeOpenedException) { }
            return null;
        }
        bool owned;
        try { owned = instance._mutex.WaitOne(wait); }
        catch (AbandonedMutexException) { owned = true; } // the other one crashed: its run is over
        if (owned)
        {
            instance._owned = true;
            return instance;
        }
        AllowForeground();
        instance._show.Set();
        instance.Dispose();
        return null;
    }

    // Let the running copy take the foreground (Windows only lets the app the player just started do that).
    private static void AllowForeground()
    {
        try { AllowSetForegroundWindow(AsfwAny); } catch (EntryPointNotFoundException) { } catch (DllNotFoundException) { }
    }

    /// <summary>Calls <paramref name="bringToFront"/> (on a pool thread) each time a second copy is started.</summary>
    public void OnSecondCopy(Action bringToFront)
    {
        _wait?.Unregister(null);
        _wait = ThreadPool.RegisterWaitForSingleObject(_show, (_, _) => bringToFront(), null, Timeout.Infinite, executeOnlyOnce: false);
    }

    public void Dispose()
    {
        _wait?.Unregister(null);
        _wait = null;
        if (_owned)
        {
            _owned = false;
            try { _mutex.ReleaseMutex(); } catch (ApplicationException) { } // released from another thread: the handle close frees it
        }
        _mutex.Dispose();
        _show.Dispose();
    }

    private const int AsfwAny = -1;

    [DllImport("user32.dll")]
    private static extern bool AllowSetForegroundWindow(int processId);
}
