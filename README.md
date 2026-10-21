# TV Browser (Android TV)

Early working foundation for a remote-operated Android TV browser with ad-request filtering, popup blocking, media detection, playback and downloads.

## Build
GitHub Actions > Build Android TV APK > Run workflow. Download the `tv-browser-debug-apk` artifact, extract and install the APK. No secrets required.

## Current limitations
Video detection covers directly exposed MP4/WebM/HLS/DASH URLs and HTML5 media elements, not DRM or encrypted sources. The player remembers position for URLs it opens. Downloads use Android DownloadManager for ordinary HTTP media URLs and Android Storage Access Framework for user-selected USB folders; segmented HLS/DASH streams are not downloadable yet. Filtering is a starter blocklist, not full EasyList compatibility. Phone touchpad, tabs, bookmarks and a complete downloads library are planned, not yet implemented.

Only download media you have permission to save.
