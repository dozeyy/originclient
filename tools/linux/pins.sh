# Pinned packaging tools for tools/linux/package.sh (sourced, never run on its own). Each download
# is refused unless its SHA-256 matches. Hashes are GitHub's own release-asset digests, read from
# the releases API on 2026-11-02. Bump a tool = change its URL and hash together.

# appimagetool 1.9.1 (2025-11-18): turns the AppDir into the .AppImage. It runs as an AppImage
# itself; package.sh starts it with APPIMAGE_EXTRACT_AND_RUN=1 so the runner needs no FUSE for it.
APPIMAGETOOL_URL_x86_64="https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
APPIMAGETOOL_SHA_x86_64="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
APPIMAGETOOL_URL_aarch64="https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-aarch64.AppImage"
APPIMAGETOOL_SHA_aarch64="f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158"

# The AppImage runtime (type 2, static, 20251108): the small program at the front of every
# .AppImage that mounts it. Passed with --runtime-file so appimagetool never fetches an unpinned one.
RUNTIME_URL_x86_64="https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64"
RUNTIME_SHA_x86_64="2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d"
RUNTIME_URL_aarch64="https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-aarch64"
RUNTIME_SHA_aarch64="00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444"

# nfpm 2.48.0 (2026-10-12): builds the .deb and the .rpm from one config (tools/linux/nfpm.yaml).
NFPM_URL_x86_64="https://github.com/goreleaser/nfpm/releases/download/v2.48.0/nfpm_2.48.0_Linux_x86_64.tar.gz"
NFPM_SHA_x86_64="6d7f9a28b2f397934403950fcbba83f598b08d02edbe79a0815b3b4897341750"
NFPM_URL_aarch64="https://github.com/goreleaser/nfpm/releases/download/v2.48.0/nfpm_2.48.0_Linux_arm64.tar.gz"
NFPM_SHA_aarch64="a9a75213763783b7669719e505474c748c5ad7dc66591a195619deb50f85c2f4"

# OpenSSL 3 for the AppImage and the tar.gz only (Core/Net/LinuxOpenSsl.Linux.cs loads it when the distro has no
# OpenSSL 3, e.g. Fedora 45's default OpenSSL 4, which .NET 10 can't use). Ubuntu 22.04's libssl3 3.0.2-0ubuntu1.30
# (glibc 2.34+; every distro without OpenSSL 3 is newer than that). Launchpad's librarian keeps every published file
# forever; the archive pool copy is the same file (same SHA-256) while it is the current security update. The .deb
# and .rpm don't carry it: they depend on the distro's OpenSSL 3 (nfpm.yaml). Hashes read 2026-11-08.
LIBSSL3_URLS_x86_64="https://launchpad.net/ubuntu/+archive/primary/+files/libssl3_3.0.2-0ubuntu1.30_amd64.deb http://archive.ubuntu.com/ubuntu/pool/main/o/openssl/libssl3_3.0.2-0ubuntu1.30_amd64.deb"
LIBSSL3_SHA_x86_64="d1b4636e32ac6d3b95d583ac05ac548b1aa248414bae1c51b14f54be0f8fd7bc"
LIBSSL3_URLS_aarch64="https://launchpad.net/ubuntu/+archive/primary/+files/libssl3_3.0.2-0ubuntu1.30_arm64.deb http://ports.ubuntu.com/ubuntu-ports/pool/main/o/openssl/libssl3_3.0.2-0ubuntu1.30_arm64.deb"
LIBSSL3_SHA_aarch64="1c4b8cc736eed8a0caafc385f854fa6852382221d84be4795f1d19a52b9fec53"
