# Drone Route

Map DJI drone flights on the controller and send the tracks to CalTopo. No exporting.

## Demo
- [Send flights to CalTopo](docs/send-to-caltopo.mp4)
- [Map and 3D view](docs/map-and-3d.mp4)
- [Sync videos/SRTs from the drone](docs/sync-from-drone.mp4)

## Install
- Download from **Releases** on the controller (DJI RC Pro Enterprise / RC Plus, Android 7+):
  - `DroneRoute.apk`: maps, 3D, flight logs, Send to CalTopo.
  - `DroneRoute-drone-sync.apk`: the same, plus copying SRTs/videos from the drone's SD card.
- Open it, allow install, then allow **All files access**.

## Setup (once per device)
- **DJI API key** (decrypts flight logs): developer.dji.com → My Apps → Create App → type **Open API** → activate → copy its **SDK key** → app **Settings**.
- **CalTopo**: Settings → **Sign in to CalTopo** → Sync Account in the browser → share the page back to the app (or type its code).
- **Offline maps**: on wifi, Settings → **Download maps** → pick area and layers (USGS imagery/topo, 3D terrain). Or copy `.mbtiles` to the SD card and tap **Scan**.

## Use
- Fly with Pilot 2 as usual. Flight logs are saved on the controller.
- Open Drone Route. Flights are listed newest first.
- Tap a flight for the map, **3D** for the 3D view (Color: track / speed / altitude).
- Tick flights → **Send to CalTopo** → confirm → **Share link**. One map per day (`UAS yyyy-MM-dd`).
- Drone sync (sync APK): close Pilot 2, drone on with SD card → **Sync SRTs** / **Sync videos**.

## Build
- Android SDK + NDK, JDK 17+, Rust with `cargo-ndk`.
- `cd core-rs && cargo ndk -t arm64-v8a -t x86_64 -o ../app/src/main/jniLibs build --release --lib`
- `./gradlew :app:assembleEmuDebug` (no drone sync) or `:app:assembleControllerDebug` (needs a DJI **Mobile SDK** key for `com.grizzly.droneroute` in `~/.config/dji/api_key` as `msdk_app_key=...`).
- Terrain pack for offline 3D: `python3 tools/make_terrain_mbtiles.py OUT.mbtiles S W N E 12`

Uses dji-log-parser (MIT), CesiumJS (Apache-2.0), osmdroid (Apache-2.0), DJI Mobile SDK.

## License
Free for search and rescue, public safety and other noncommercial use.
Please don't redistribute or modify it outside of contributing back here.
Issues, feature requests and pull requests welcome. Making changes to submit
a pull request is fine; by submitting one, you agree it can be included and
licensed under the same terms.
PolyForm Strict 1.0.0, see LICENSE.
