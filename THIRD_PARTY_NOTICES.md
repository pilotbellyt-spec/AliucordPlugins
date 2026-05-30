# Third-Party Notices

ModernUserStyles is licensed under the GNU General Public License version 3.0.
It also uses the following third-party projects during builds and runtime.

## khoben/woff2-android

- Source: https://github.com/khoben/woff2-android
- License: Apache License 2.0
- Usage: WOFF2-to-Typeface runtime support and native decoder source/build layout.

The repository includes a small JNI wrapper compatible with
`com.github.khoben.libwoff2dec.Woff2Decoder` so the native decoder can be
compiled from source during the Gradle build instead of committing prebuilt
native binaries.

The full upstream Apache License 2.0 text is available in the
`third_party/woff2-android/LICENSE` submodule file.

## Google WOFF2

- Source: https://github.com/google/woff2
- License: MIT
- Usage: Native WOFF2 decoder sources fetched by the `woff2-android` build script.

## Brotli

- Source: https://github.com/google/brotli
- License: MIT-style license
- Usage: Native Brotli dependency used by Google WOFF2.

