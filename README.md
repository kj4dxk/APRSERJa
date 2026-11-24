# APRSERJa

Android APRS station map, two-way messaging and offline regional navigation.

**Android · [Download APRSERJa.apk](https://github.com/kj4dxk/APRSERJa/releases/latest/download/APRSERJa.apk)** · [Release 2026.11.22.1101 and checksum](https://github.com/kj4dxk/APRSERJa/releases/tag/2026.11.22.1101)

**Windows · [Download APRSERJ64.exe](https://github.com/kj4dxk/APRSERJ64/releases/latest/download/APRSERJ64.exe)** · [Windows setup and manual](https://github.com/kj4dxk/APRSERJ64)

Requires Android 8 or later. The renamed app uses package `org.aprserja.mobile` and installs separately from the previous Android app; enter your credentials again. Existing app data is not deleted. Install the APK and open the APRS-IS connection panel. Enter your callsign and use the receive-only passcode `-1` to watch stations within 100 km of the map center. Sending over APRS-IS requires a verified login. A transmit-enabled TNC can send messages without an APRS-IS login. Numbered messages are stored locally and retried after reconnecting with the same callsign.

## Radio TNC connections

Enter your RF callsign above the connection panels and open **Radio TNC connection**. Choose a connection and protocol, select the device or enter its network address, then select **Connect TNC**. One APRS-IS session and one TNC session can run together. Receive-only is the default. Enable RF transmit when you want to send messages and acknowledgments. For the same callsign, pending messages prefer a transmit-enabled TNC and use verified APRS-IS when RF is unavailable. Incoming numbered messages are acknowledged on their receive transport when it permits transmission. Leave the digipeater path blank for direct operation, or enter a comma-separated path for your area.

| Setup | Select in APRSERJa |
| --- | --- |
| USB KISS TNCs and serial adapters, including Raspberry Pi Pico TNCs configured for KISS and NinoTNC | **USB serial → KISS**. Use a USB host/OTG data cable, refresh the device list, and match the TNC baud rate (57600 for NinoTNC). Serial uses 8N1. |
| Serial Profile TNCs and radios | Pair in Android Settings, then select **Bluetooth Classic → KISS** and refresh. Put the radio in external KISS mode where applicable. |
| Mobilinkd TNC3/TNC4, BLE KISS radios such as the BTECH UV-PRO, and ESP32 firmware advertising BLE KISS or Nordic UART | Enable the device's KISS service, then select **Bluetooth LE → KISS** and refresh. Android prompts for Nearby devices on Android 12 and up, and for location on earlier versions. |
| Dire Wolf on a Raspberry Pi or computer, including DigiPi or a setup with a USB sound card or AIOC | Configure the audio modem and PTT in Dire Wolf. Connect using **TCP → KISS** to the modem host, normally port 8001. Use **TCP → AGWPE**, normally port 8000, for an endpoint that exposes raw AGW frames. |
| ESP32 or other firmware providing raw TNC2 text | Select **TNC2 text** with the advertised serial or TCP link. Enable transmit only if that firmware accepts outbound TNC2 packets. |

The compatibility table describes available protocol paths. Radio mode, audio, PTT, and firmware configuration use the corresponding device or modem tools. APRSERJa connects to a software modem over its TNC interface. Direct phone audio modulation and vendor firmware updates are outside this build.

[BLE KISS specification](https://github.com/hessu/aprs-specs/blob/master/BLE-KISS-API.md) · [Mobilinkd firmware and guides](https://www.mobilinkd.com/downloads/) · [Dire Wolf configuration](https://github.com/wb2osz/direwolf/blob/master/conf/generic.conf) · [ESP32 KISS firmware](https://github.com/sh123/esp32_loraprs) · [Raspberry Pi Pico TNC](https://github.com/amedes/pico_tnc) · [NinoTNC](https://tarpn.net/t/nino-tnc/n9600a/n9600a_operation_new.html) · [DigiPi](https://digipi.org/) · [BTECH UV-PRO firmware](https://baofengtech.com/uv-pro-firmware-changelog/) · [AIOC](https://github.com/skuep/AIOC) · [USB Serial for Android](https://github.com/mik3y/usb-serial-for-android)

### Mobile radio operation

Open a local or previously imported OSM region and select **Start GPS**. GPS, the map, GPX recording, and the TNC work without cellular service. GPS and the TNC continue while the screen is off through a foreground service. Use the app controls or the ongoing notification to stop the session. After Android stops the process, reopen the app and explicitly start the radio and GPS. A network TNC still needs its local Wi-Fi or hotspot. GPX recording defaults to on. **Export latest GPX** saves a copy through the Android file picker.

A dropped TNC connection retries with a delay increasing from one second to one minute. Disconnect cancels retries. To transmit position reports, enable RF transmit, choose **GeniusBeaconing** or **SmartBeaconing**, and start GPS. GeniusBeaconing uses the shared interval, distance, heading, and forecast-error policy. SmartBeaconing uses speed-dependent intervals and corner triggers.

Rig diagnostics identifies the protocol and radio channel, counts received packets and successful writes per connection, and discovers advertised KISS services on the local network. Select a result to fill the connection fields. **Read Mobilinkd audio and PTT settings** requests TX audio gain and PTT style from compatible KISS firmware. A returned setting identifies configuration. An acknowledged RF message tests the round trip through the radio.

## Maps and navigation

A small downtown Boston extract ships with the app. Import an OpenStreetMap XML extract under 20 MB to use another region. Search for an address, set the start, search for the destination and select **Drive here** or **Walk here**. Start GPS to follow your position and use it as the driving origin. Driving directions use a locally installed English Android text-to-speech voice. GPS recording continues with the screen off. Keep the map open for voice guidance.

Driving uses shortest distance, road access tags and directed road segments. Driving honors ordinary node-based turn restrictions and recalculates the route after sustained GPS deviation. Restrictions that pass through another way, conditional access, lane guidance, and live traffic remain outside this preview. Check road signs and local rules.

## Build and test

Clone with `--recurse-submodules` or run `git submodule update --init`. Use JDK 17, Android SDK 36 and the included Gradle wrapper. Run `./gradlew assembleDebug assembleRelease bundleRelease`. For a release, set version.txt to a new America/New_York timestamp in YYYY.MM.DD.HHmm format and use the same Git tag. The public APK uses a release signing key.

The debug build includes emulator smoke tests for shared APRS-IS reception, two-way chat, legacy outbox import, recovery after force-stop, receive-only login, offline routing and GPS. The TNC smoke tests cover simulated KISS, raw AGWPE, and TNC2 endpoints, including reception with RF transmit disabled.

## Credits and licenses

New application code uses the GNU Affero General Public License v3. See [LICENSE](LICENSE). The project follows the license choice of [APRSISMO](https://github.com/APRSFoundation/APRSISMO). Android owns the map and routing interface and uses [aprs-core](https://github.com/kj4dxk/aprs-core) for APRS-IS, packet decoding and durable chats. The app imports the existing history and outbox for this app installation on first launch after updating.

The bundled Boston map data is © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/). The original XML is included in the source and APK.
