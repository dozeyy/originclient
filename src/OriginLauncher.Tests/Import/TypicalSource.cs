namespace OriginLauncher.Tests.Import;

/// <summary>A realistic source game folder: settings, packs, shaders, configs, mods and a world.</summary>
internal static class TypicalSource
{
    public static readonly DateTime Old = new(2020, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    public const string Options =
        "version:3955\nao:true\nfov:0.5\nrenderDistance:12\nkey_key.forward:key.keyboard.w\nkey_key.jump:key.keyboard.space\n"
        + "highContrast:true\nnarrator:1\nlang:en_us\n"
        + "resourcePacks:[\"vanilla\",\"file/Faithful.zip\",\"file/FolderPack\",\"file/Missing.zip\"]\nincompatibleResourcePacks:[]\n";

    public static void Populate(string source, bool withMods = true)
    {
        Kit.Write(source, "options.txt", Options, Old);
        File.SetLastWriteTimeUtc(Kit.WriteBytes(source, "servers.dat", Kit.ServersDat(("SERVERLIST Hub", "play.example.net"))), Old);
        Kit.WriteBytes(source, "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "faithful")));
        Kit.Write(source, "resourcepacks/FolderPack/pack.mcmeta", "{}");
        Kit.WriteBytes(source, "resourcepacks/Unused.zip", Kit.Zip(Kit.Text("pack.mcmeta", "unused")));
        Kit.WriteBytes(source, "shaderpacks/Complementary.zip", Kit.Zip(Kit.Text("shaders/a.fsh", "x")));
        Kit.Write(source, "shaderpacks/Complementary.zip.txt", "SHADOW=2048\n");
        Kit.WriteBytes(source, "shaderpacks/OtherShader.zip", Kit.Zip(Kit.Text("shaders/b.fsh", "y")));
        Kit.Write(source, "config/iris.properties", "shaderPack=Complementary.zip\nenableShaders=true\nmaxShadowRenderDistance=16\n", Old);
        Kit.Write(source, "config/sodium-options.json",
            """{"quality":{"weather_quality":"FAST"},"advanced":{"cpu_render_ahead_limit":3}}""", Old);
        Kit.Write(source, "config/somemod.json", """{"a":1}""", Old);
        Kit.Write(source, "config/nested/x.toml", "x=1", Old);
        Kit.Write(source, "config/originclient-mods.json", "{}", Old);
        Kit.Write(source, "config/sodium-mixins.properties", "a=b");
        Kit.Write(source, "config/lithium.properties", "a=b");
        Kit.Write(source, "config/immediatelyfast.json", "{}");
        Kit.Write(source, "config/krypton.toml", "a=b");
        Kit.Write(source, "config/discord-token.json", "{\"t\":\"x\"}");
        Kit.Write(source, "saves/World1/level.dat", "LEVEL");
        Kit.Write(source, "saves/World1/region/r.0.0.mca", "REGION");
        Kit.Write(source, "saves/World1/session.lock", "lock");
        if (!withMods) return;
        Kit.WriteBytes(source, "mods/mymod.jar", Kit.FabricJar("mymod", "1.0", minecraft: "~1.21.1", depends: new() { ["fabric-api"] = "*" }));
        Kit.WriteBytes(source, "mods/sodium-fabric.jar", Kit.FabricJar("sodium", "0.5.0"));
        Kit.WriteBytes(source, "mods/needs-lib.jar", Kit.FabricJar("needslib", "1.0", depends: new() { ["ghostlib"] = ">=2.0" }));
        Kit.WriteBytes(source, "mods/forge-thing.jar", Kit.Zip(Kit.Text("META-INF/mods.toml", "[[mods]]\nmodId=\"forgething\"\n")));
        Kit.WriteBytes(source, "mods/old-thing.jar", Kit.FabricJar("oldthing", "1.0", minecraft: "~1.20.4"));
        Kit.WriteBytes(source, "mods/disabled-thing.jar.disabled", Kit.FabricJar("disabledthing", "1.0"));
    }
}
