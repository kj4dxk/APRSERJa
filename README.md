# APRSERJa

Android APRS station map, two-way messaging and offline regional navigation.

**Android · [Download APRSERJa.apk](https://github.com/kj4dxk/APRSERJa/releases/latest/download/APRSERJa.apk)** · [Release 2026.11.22.1101 and checksum](https://github.com/kj4dxk/APRSERJa/releases/tag/2026.11.22.1101)

**Windows · [Download APRSERJ64.exe](https://github.com/kj4dxk/APRSERJ64/releases/latest/download/APRSERJ64.exe)** · [Windows setup and manual](https://github.com/kj4dxk/APRSERJ64)

Requires Android 8 or later. The renamed app uses package `org.aprserja.mobile` and installs separately from the previous Android app; enter your credentials again. Existing app data is not deleted. Install the APK and open the APRS-IS connection panel. Enter your callsign and use the receive-only passcode `-1` to watch stations within 100 km of the map center. Sending messages requires a verified APRS-IS login. Numbered messages are stored locally and retried after reconnecting with the same callsign.

## Maps and navigation

A small downtown Boston extract ships with the app. Import an OpenStreetMap XML extract under 4 MB to use another region. Search for an address, set the start, search for the destination and select **Drive here** or **Walk here**. Start GPS to follow your position and use it as the driving origin. Driving directions use a locally installed English Android text-to-speech voice. Keep the app open for tracking and guidance.

Driving uses shortest distance, road access tags and directed road segments. This preview does not process turn-restriction relations, conditional access, lane guidance, traffic or automatic rerouting. Check road signs and local rules.

## Build and test

Clone with `--recurse-submodules` or run `git submodule update --init`. Use JDK 17, Android SDK 36 and the included Gradle wrapper. Run `./gradlew assembleDebug assembleRelease bundleRelease`. For a release, set version.txt to a new America/New_York timestamp in YYYY.MM.DD.HHmm format and use the same Git tag. The public APK uses a release signing key.

The debug build includes emulator smoke tests for shared APRS-IS reception, two-way chat, legacy outbox import, recovery after force-stop, receive-only login, offline routing and GPS.

## Credits and licenses

New application code uses the GNU Affero General Public License v3. See [LICENSE](LICENSE). The project follows the license choice of [APRSISMO](https://github.com/APRSFoundation/APRSISMO). Android owns the map and routing interface and uses [aprs-core](https://github.com/kj4dxk/aprs-core) for APRS-IS, packet decoding and durable chats. The app imports the existing history and outbox for this app installation on first launch after updating.

The bundled Boston map data is © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/). The original XML is included in the source and APK.

