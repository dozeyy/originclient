using OriginLauncher.App.Core.Promos;
using Xunit;

namespace OriginLauncher.Tests;

public class OriginPlusMembershipTests
{
    private const string Uuid = "0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9";
    private const string Normal = "0a1b2c3d4e5f60718293a4b5c6d7e8f9";
    private static readonly Uri Api = new("https://cosmetics.example/");

    private sealed class Clock
    {
        public DateTime Now = new(2026, 10, 17, 12, 0, 0, DateTimeKind.Utc);
    }

    private static string Player(bool plus) =>
        "{\"uuid\":\"" + Normal + "\",\"owned\":[],\"equipped\":{\"cape\":null,\"head\":null},\"plus\":"
        + (plus ? "true" : "false") + "}";

    private static OriginPlusMembership Make(HttpTextGet get, Clock? clock = null) =>
        new(get, Api, clock == null ? null : () => clock.Now);

    [Theory]
    [InlineData("{\"plus\":true}", true)]
    [InlineData("{\"plus\":false}", false)]
    [InlineData("{\"uuid\":\"x\",\"plus\":true,\"owned\":[]}", true)]
    [InlineData("{\"plus\":\"true\"}", null)]
    [InlineData("{\"plus\":1}", null)]
    [InlineData("{\"plus\":null}", null)]
    [InlineData("{}", null)]
    [InlineData("[true]", null)]
    [InlineData("not json", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    public void ParsePlusReadsOnlyAClearBoolean(string? body, bool? expected) =>
        Assert.Equal(expected, OriginPlusMembership.ParsePlus(body));

    [Theory]
    [InlineData(Uuid, Normal)]
    [InlineData("0A1B2C3D4E5F60718293A4B5C6D7E8F9", Normal)]
    [InlineData("  " + Uuid + " ", Normal)]
    [InlineData("pending-1234", null)]
    [InlineData("offline", null)]
    [InlineData("0a1b2c3d4e5f60718293a4b5c6d7e8f", null)]   // 31 chars
    [InlineData("zz1b2c3d4e5f60718293a4b5c6d7e8f9", null)]  // not hex
    [InlineData("", null)]
    [InlineData(null, null)]
    public void NormalizeUuidAcceptsOnlyRealProfileIds(string? id, string? expected) =>
        Assert.Equal(expected, OriginPlusMembership.NormalizeUuid(id));

    [Fact]
    public async Task AsksThePlayerEndpointAndReturnsPlus()
    {
        Uri? asked = null;
        var membership = Make((url, _, _) =>
        {
            asked = url;
            return Task.FromResult(new HttpTextResult(200, Player(true)));
        });

        Assert.True(await membership.IsMemberAsync(Uuid, CancellationToken.None));
        Assert.Equal(new Uri($"https://cosmetics.example/v1/players/{Normal}"), asked);
    }

    [Fact]
    public async Task NonMemberIsFalse()
    {
        var membership = Make((_, _, _) => Task.FromResult(new HttpTextResult(200, Player(false))));
        Assert.False(await membership.IsMemberAsync(Uuid, CancellationToken.None));
    }

    [Theory]
    [InlineData(0, null)]               // offline / timeout
    [InlineData(500, null)]
    [InlineData(404, null)]
    [InlineData(200, "garbage")]
    [InlineData(200, "{\"nope\":1}")]
    public async Task AnyFailureIsNotAMember(int status, string? body)
    {
        var membership = Make((_, _, _) => Task.FromResult(new HttpTextResult(status, body)));
        Assert.False(await membership.IsMemberAsync(Uuid, CancellationToken.None));
    }

    [Fact]
    public async Task AThrowingFetchIsNotAMember()
    {
        var membership = Make((_, _, _) => throw new InvalidOperationException("boom"));
        Assert.False(await membership.IsMemberAsync(Uuid, CancellationToken.None));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("pending-abc")]
    [InlineData("offline")]
    public async Task AccountsWithoutARealUuidNeverHitTheNetwork(string? id)
    {
        var calls = 0;
        var membership = Make((_, _, _) =>
        {
            calls++;
            return Task.FromResult(new HttpTextResult(200, Player(true)));
        });

        Assert.True(membership.TryGetCached(id, out var cachedMember));
        Assert.False(cachedMember);
        Assert.False(await membership.IsMemberAsync(id, CancellationToken.None));
        Assert.Equal(0, calls);
    }

    [Fact]
    public async Task AnAnswerIsCachedForTheFreshWindowThenAsksAgain()
    {
        var clock = new Clock();
        var calls = 0;
        var plus = true;
        var membership = Make((_, _, _) =>
        {
            calls++;
            return Task.FromResult(new HttpTextResult(200, Player(plus)));
        }, clock);

        Assert.False(membership.TryGetCached(Uuid, out _));
        Assert.True(await membership.IsMemberAsync(Uuid, CancellationToken.None));

        clock.Now += OriginPlusMembership.FreshFor - TimeSpan.FromSeconds(1);
        Assert.True(membership.TryGetCached(Uuid, out var cached));
        Assert.True(cached);
        Assert.True(await membership.IsMemberAsync(Uuid, CancellationToken.None));
        Assert.Equal(1, calls);

        // The banner's 30-minute tick always lands after the cache went stale.
        plus = false;
        clock.Now += TimeSpan.FromMinutes(30) - (OriginPlusMembership.FreshFor - TimeSpan.FromSeconds(1));
        Assert.False(membership.TryGetCached(Uuid, out _));
        Assert.False(await membership.IsMemberAsync(Uuid, CancellationToken.None));
        Assert.Equal(2, calls);
    }

    [Fact]
    public async Task AFailedLookupRetriesSoonNotAfterHalfAnHour()
    {
        var clock = new Clock();
        var calls = 0;
        var up = false;
        var membership = Make((_, _, _) =>
        {
            calls++;
            return Task.FromResult(up ? new HttpTextResult(200, Player(true)) : new HttpTextResult(0, null));
        }, clock);

        Assert.False(await membership.IsMemberAsync(Uuid, CancellationToken.None));
        Assert.False(await membership.IsMemberAsync(Uuid, CancellationToken.None));
        Assert.Equal(1, calls); // inside the retry window: no hammering

        up = true;
        clock.Now += OriginPlusMembership.FailureRetry + TimeSpan.FromSeconds(1);
        Assert.True(await membership.IsMemberAsync(Uuid, CancellationToken.None));
        Assert.Equal(2, calls);
    }

    [Fact]
    public async Task EachAccountHasItsOwnCacheEntry()
    {
        var asked = new List<string>();
        var membership = Make((url, _, _) =>
        {
            asked.Add(url.AbsolutePath);
            return Task.FromResult(new HttpTextResult(200, Player(url.AbsolutePath.EndsWith(Normal, StringComparison.Ordinal))));
        });
        const string other = "ffffffffffffffffffffffffffffffff";

        Assert.True(await membership.IsMemberAsync(Uuid, CancellationToken.None));
        Assert.False(await membership.IsMemberAsync(other, CancellationToken.None));
        Assert.True(await membership.IsMemberAsync(Uuid.ToUpperInvariant(), CancellationToken.None)); // same uuid, other spelling
        Assert.Equal(2, asked.Count);
    }

    [Fact]
    public async Task ACancelledCallerIsNotSwallowed()
    {
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        var membership = Make((_, _, ct) =>
        {
            ct.ThrowIfCancellationRequested();
            return Task.FromResult(new HttpTextResult(200, Player(true)));
        });

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => membership.IsMemberAsync(Uuid, cts.Token));
    }
}
