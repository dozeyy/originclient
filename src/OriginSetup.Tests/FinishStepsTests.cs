using System;
using System.Collections.Generic;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// The steps after the launcher is in place (Installer.Finish). Bug run 6, KD-4: a desktop shortcut that Windows
// refused (Controlled folder access) skipped the Add/Remove Programs entry and showed "Origin wasn't installed"
// over a launcher that was already installed.
public class FinishStepsTests
{
    [Fact]
    public void A_refused_shortcut_never_stops_the_steps_after_it_and_becomes_a_note()
    {
        var ran = new List<string>();
        var notes = Installer.Finish(new[]
        {
            new Installer.FinishStep("Add/Remove Programs entry", () => ran.Add("registration"), "no entry"),
            new Installer.FinishStep("desktop shortcut",
                () => throw new UnauthorizedAccessException("Access is denied."), "no desktop shortcut"),
            new Installer.FinishStep("Start with Windows", () => ran.Add("startup"), "no startup"),
            new Installer.FinishStep("Customize choices", () => ran.Add("handoff"), "no handoff"),
        });

        Assert.Equal(new[] { "registration", "startup", "handoff" }, ran);
        Assert.Equal(new[] { "no desktop shortcut" }, notes);
    }

    [Fact]
    public void Every_refusal_gets_its_own_note_in_order()
    {
        var notes = Installer.Finish(new[]
        {
            new Installer.FinishStep("a", () => throw new System.Runtime.InteropServices.COMException("blocked"), "first"),
            new Installer.FinishStep("b", () => { }, "never"),
            new Installer.FinishStep("c", () => throw new System.IO.IOException("busy"), "second"),
        });

        Assert.Equal(new[] { "first", "second" }, notes);
    }

    [Fact]
    public void Nothing_refused_means_no_notes()
    {
        Assert.Empty(Installer.Finish(new[] { new Installer.FinishStep("a", () => { }, "never") }));
    }
}
