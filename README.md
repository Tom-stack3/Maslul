# <img src="docs/logo.svg" width="36" alt="" align="top"> Maslul

<img src="docs/screenshots/phones.png" width="360" align="right" alt="Route options with live arrival times, and a route with a choice of buses and their live locations">

A free, open-source public transport app for Israel (Android). No ads, no accounts, no tracking. It's serverless: the app talks directly to public open-data services, so there's nothing to host or pay for.

## Download

Get the latest APK from the [Releases](../../releases) page. A Play Store release will probably come in the future.

## Features

- Route planning, combining buses that ride the same stretch, with a pick of which bus to take
- Live arrivals, color-coded by how fresh the data is (live / stale / timetable only)
- Live bus locations on the map, updated automatically
- Warns about tight transfers ("2 min to catch 143 after a 200 m walk") and says what happens if you miss it: the next bus and how much later you'd be
- Line and stop screens, favorites, trip reminders
- In English, Hebrew (right to left) or Russian: follows the phone, or pick one in Settings
- When nothing leaves soon nearby (late at night, on Shabbat), also looks for rides up to a 50-minute walk away instead of only offering tomorrow's first bus
- Operator colors (Egged, Dan, Metropoline, …) and bus platform numbers at big stations
- **Simple Maslul**: an optional mode with six big buttons and plain step-by-step directions, in English, Hebrew or Russian. Made for grandparents; turn it on in Settings
- [Advanced tools](docs/advanced-features.md) for trips a normal planner can't help with:
  - **Getting a ride (טרמפ):** someone's driving you part of the way. Tell Maslul where the driver is heading, or how far they'll take you, and it finds the best place to be dropped off to continue by bus or train
  - **Already on a bus:** pick the bus or train you're on and where you're going. Maslul tells you where to get off, and whether changing at the next stop is better
  - **My shuttles:** add shuttles that aren't in the public timetables, like a company bus to the train station, and trips will use them alongside buses and trains

## Data sources

- **Routes & timetables:** [Transitous](https://transitous.org) (MOTIS), built on the Ministry of Transport GTFS feed
- **Live vehicles:** the Ministry of Transport SIRI feed, via [Hasadna's Open Bus](https://github.com/hasadna/open-bus-siri-requester) public snapshots
- **Line data:** [Open Bus Stride API](https://open-bus-stride-api.hasadna.org.il)
- **Maps:** [OpenFreeMap](https://openfreemap.org) / OpenStreetMap
- **Address search:** Photon and Nominatim (OpenStreetMap)

## Building

Requires Android Studio (or the Android SDK) and JDK 17+.

```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # run unit tests
./gradlew installDebug         # install on a connected device
```

## Contributing

Ideas, bug reports and changes are welcome. [Open an issue](../../issues) for a feature request or bug, or send a pull request.

## License

[GNU Affero General Public License v3.0](LICENSE). You're free to use, change and share Maslul, as long as your version stays open source under the same license.
