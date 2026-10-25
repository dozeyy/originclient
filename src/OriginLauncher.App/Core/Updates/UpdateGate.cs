namespace OriginLauncher.App.Core.Updates;

// The mandatory-update gate's state machine, pure (no WPF, no network, the clock is a parameter) so the tests
// can drive it. UpdateService owns one and feeds it what the release feed and the downloads say.
//
// Rule (Will / mandate 4 "never broken"): only an update that is KNOWN to be good can stop the player from
// launching. So:
//  - A failed integrity check (UpdateVerificationException) takes the update out of the gate at once
//    (Required goes false: the player launches the version they already have) and starts a back-off, 30 min
//    doubling to 6 h while it keeps failing. A new process starts with no back-off, so the next start retries too.
//  - While the back-off runs the feed's answer is ignored (Wait). Once it has run out the update is NOT put back
//    in front of the player: it is fetched and verified quietly first (Quiet), and only a verified download is
//    published to the gate (Publish), so a broken release can never block play twice.
//  - A first sighting of a version is published straight away (the pre-existing behaviour: nothing has failed yet).
public sealed class UpdateGate
{
    public static readonly TimeSpan FirstRetry = TimeSpan.FromMinutes(30);
    public static readonly TimeSpan MaxRetry = TimeSpan.FromHours(6);

    public enum Decision
    {
        /// <summary>Nothing to do: not newer than this build, or already published.</summary>
        Skip,
        /// <summary>Offer it: the badge lights and, on a CI build, Play is gated.</summary>
        Publish,
        /// <summary>A recent check failed: leave it alone for now.</summary>
        Wait,
        /// <summary>Back-off over: fetch and verify it silently; publish only if that succeeds.</summary>
        Quiet,
        /// <summary>The release carries no checksum at all (<see cref="UpdateVerifier"/> would refuse it): never offered, never gated, backed off.</summary>
        Refuse
    }

    private Version? _published;
    private Version? _failedVersion;
    private int _failures;
    private DateTime _retryAtUtc;
    private bool _quietInFlight;
    private Version? _plainVersion;
    private int _plainFailures;

    /// <summary>The version currently offered to the player (and gating Play on a CI build), if any.</summary>
    public Version? Published => _published;

    /// <summary>True when Play must wait for an update. Only a published (verified or not-yet-failed) update counts.</summary>
    public bool Required(bool isDevBuild) => _published != null && !isDevBuild;

    public Decision Evaluate(Version remote, Version current, DateTime nowUtc)
    {
        if (Normalize(remote) <= Normalize(current)) return Decision.Skip;
        if (_published != null && Normalize(_published) >= Normalize(remote)) return Decision.Skip;

        // A failure on an older version says nothing about a newer one: it is a fresh release.
        if (_failedVersion == null || Normalize(remote) > Normalize(_failedVersion))
            return Decision.Publish;

        if (_quietInFlight || nowUtc < _retryAtUtc) return Decision.Wait;
        _quietInFlight = true;
        return Decision.Quiet;
    }

    /// <summary>
    /// <see cref="Evaluate"/> for a release whose verifiability is already known from the feed alone: with
    /// <paramref name="hasHash"/> false (no .sha256 asset and no usable digest) the download could only ever be refused,
    /// so the update is never offered and never gates Play. It is treated like a failed check (back-off, then the same
    /// quiet path), which is <see cref="Decision.Refuse"/>. Anything Evaluate would skip or wait on stays that way.
    /// </summary>
    public Decision EvaluateRelease(Version remote, Version current, bool hasHash, DateTime nowUtc)
    {
        var decision = Evaluate(remote, current, nowUtc);
        if (hasHash || decision is not (Decision.Publish or Decision.Quiet)) return decision;
        VerifyFailed(remote, nowUtc);
        return Decision.Refuse;
    }

    public void Publish(Version version) => _published = version;

    /// <summary>
    /// Publish unless this version or a newer one is already offered (a late quiet retry of X must not
    /// overwrite the offer of a newer Y). Returns whether it was published.
    /// </summary>
    public bool TryPublish(Version version)
    {
        if (_published != null && Normalize(_published) >= Normalize(version)) return false;
        _published = version;
        return true;
    }

    /// <summary>Consecutive non-verification failures (disk, antivirus, stall, HTTP) before Play is released.</summary>
    public const int PlainFailuresBeforeUngate = 2;

    /// <summary>
    /// Any failure that is not an integrity failure (UpdateService.DownloadAndRestartAsync). The first leaves the
    /// update gated (the player gets "click to retry"); the second in a row releases Play with the same quiet
    /// back-off as a failed check, so no failure mode can block play for good. Returns true when it released Play.
    /// </summary>
    public bool DownloadFailed(Version version, DateTime nowUtc)
    {
        if (_plainVersion == null || Normalize(_plainVersion) != Normalize(version))
        {
            _plainVersion = version;
            _plainFailures = 0;
        }
        if (++_plainFailures < PlainFailuresBeforeUngate) return false;
        return VerifyFailed(version, nowUtc);
    }

    /// <summary>The integrity check (or the quiet fetch) failed: ungate it and back off. Always returns true.</summary>
    public bool VerifyFailed(Version version, DateTime nowUtc)
    {
        _plainVersion = null;
        _plainFailures = 0;
        if (_published != null && Normalize(_published) <= Normalize(version)) _published = null;
        _failures = _failedVersion != null && Normalize(_failedVersion) == Normalize(version) ? _failures + 1 : 1;
        _failedVersion = version;
        _quietInFlight = false;
        var minutes = Math.Min(FirstRetry.TotalMinutes * Math.Pow(2, _failures - 1), MaxRetry.TotalMinutes);
        _retryAtUtc = nowUtc + TimeSpan.FromMinutes(minutes);
        return true;
    }

    /// <summary>A quiet fetch passed its check: forget the failures so the caller can publish it.</summary>
    public void Verified(Version version)
    {
        if (_failedVersion != null && Normalize(_failedVersion) <= Normalize(version))
        {
            _failedVersion = null;
            _failures = 0;
        }
        _quietInFlight = false;
        _plainVersion = null;
        _plainFailures = 0;
    }

    // System.Version treats unset components as -1, which makes "1.0.5" and "1.0.5.0" unequal; pad to four.
    public static Version Normalize(Version v) => new(
        Math.Max(v.Major, 0), Math.Max(v.Minor, 0),
        Math.Max(v.Build, 0), Math.Max(v.Revision, 0));
}
