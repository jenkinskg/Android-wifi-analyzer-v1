# URL to MP3 Android v2

A standalone Android APK with YouTube artist/song search (ytsearch15), multi-select of search results, sequential batch conversion to MP3, direct URL conversion, selectable MP3 bitrates, cancel, and a button to update yt-dlp. The app also attempts a yt-dlp update at startup.

Audio files save to Music / URL to MP3. No Termux required. Android 10+.

Use only with videos/audio you own or are authorized to download. Search results do not convey download rights. Some YouTube streams may return HTTP 403 even after updating yt-dlp, in which case the app cannot download that stream.

Build via GitHub Actions on branch url-to-mp3-v2. Uses yt-dlp Android library (GPL-3.0) from https://github.com/yausername/youtubedl-android .
