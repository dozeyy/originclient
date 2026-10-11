// The installer targets .NET Framework 4.7.2 with ImplicitUsings off: keep the
// global set to namespaces every target has. Files that need System.Net.Http,
// System.IO.Compression or System.Runtime.Serialization say so themselves
// (their assemblies are referenced explicitly in OriginSetup.csproj).
global using System;
global using System.Collections.Generic;
global using System.IO;
global using System.Linq;
