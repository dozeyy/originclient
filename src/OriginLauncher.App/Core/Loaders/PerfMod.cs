namespace OriginLauncher.App.Core.Loaders;

// A single performance-mod jar pinned to an exact Modrinth version + direct
// download URL, captured from a live API snapshot rather than "latest" — see
// PerformanceModCatalog.Data.cs for why exact pins matter (Indium in
// particular breaks silently if paired with the wrong Sodium build).
public sealed record PerfMod(string Version, string Url, string FileName);
