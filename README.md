# NOVA TV Browser

Android TV browser built with **Jetpack Compose for TV**, a modern floating-control UI, Media3 fullscreen player, and local Wi-Fi phone remote.

## UI stack
- Kotlin 2.0.21 and Compose Compiler plugin
- Jetpack Compose BOM 2025.02.00, Compose for TV Material, Material Icons
- Android WebView embedded using Compose AndroidView
- Media3 ExoPlayer with resume and subtitle selection
- NanoHTTPD pairing portal with QR code (ZXing)

## Navigation
- TV remote directional pad moves a visible cursor; OK taps at its position
- Hardware mouse moves independently and hides the virtual cursor
- Move the cursor to the top or bottom edge of a webpage to scroll continuously
- Move cursor to far left to open the floating side drawer
- Enter/Go on the TV keyboard navigates from the address field; no extra Go button on toolbar
- Home screen includes quick links, search, and a QR code for phone pairing
- Phone must be on the same local Wi-Fi network as the TV; QR address discovers current IP and available port

## Media
- Detect direct video file URLs (MP4, WebM, M4V) and manifest URLs (HLS M3U8, DASH MPD) where exposed by WebView
- Prompt to open video in fullscreen player, with playback position saved
- Embedded subtitles when provided by source; load external SRT or VTT through Android document picker
- Direct downloads to app's TV movies directory, or user-selected USB destination via Android Storage Access Framework

## Known limitations
- Website DRM, opaque blob URLs, encrypted media, signed URLs, and cross-origin restrictions can prevent extraction or downloading
- Downloads of segmented HLS/DASH streams are not implemented; DownloadManager only handles direct files
- Basic host-based ad blocking is not comprehensive
- Remote cursor, Compose focus behaviour, and edge scrolling require physical Android TV testing
- No tabs or full browser history implementation yet

## Build
Use [GitHub Actions](https://github.com/baloyi286-hub/tv-browser/actions/workflows/android-build.yml) to compile the debug APK. The new Compose migration is committed but has not yet been confirmed by a successful CI build.
