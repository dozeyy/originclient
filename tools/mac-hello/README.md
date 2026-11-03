# mac-hello (throwaway probe)

Answers one question before the Mac launcher is built: can a tiny Avalonia app be packaged the way
Origin will ship (ad-hoc signed `.app`, drag-to-Applications DMG, zip), and can its embedded
WKWebView catch the Microsoft sign-in redirect?

Builds and runs only on GitHub (`.github/workflows/mac-hello.yml`, macos-15); nothing here is built on
Will's PC. It never signs in and never handles a credential: the redirects it catches are simulated
ones plus Microsoft's own error reply to an unknown scope (a real server 302).

Delete this folder and the workflow once the real Mac launcher project exists.
