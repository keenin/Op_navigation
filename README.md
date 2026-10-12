# OP Nav

A small Android app that sends a navigation destination to a comma device running openpilot **nav alpha**.

One tap on a favorite calls openpilot's Athena method `setNavDestination`. There is also a one-off address field and a button that calls `clearNavDestination`.

The device has to be on a build that includes nav alpha. That landed on openpilot master in commit [`4c362ca`](https://github.com/commaai/openpilot/commit/4c362ca3272c7418a068d43c402c84bd0544f35a) (`nav alpha (#39066)`). Older openpilot builds do not have these methods.

## Install

Download the debug APK from the latest successful [Android](https://github.com/keenin/Op_navigation/actions) workflow (artifact `op-navigation-debug`), or from [Releases](https://github.com/keenin/Op_navigation/releases) after a `v*` tag.

Unzip the artifact and install `app-debug.apk`. On a computer with the phone connected:

```bash
adb install -r app-debug.apk
```

Installing a newer build over the old one keeps the JWT, dongle ID, and favorites. The APK is signed with the debug keystore in this repo (`keystore/debug.keystore`), so Android treats it as the same app. Uninstalling wipes that data.

You can also build it yourself (JDK 17+, Android SDK 35):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or wherever your SDK lives
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Get a JWT

The token is your comma account token, the same one Connect uses.

1. Open [jwt.comma.ai](https://jwt.comma.ai).
2. Sign in with Google, Apple, or GitHub — the same account you use at [connect.comma.ai](https://connect.comma.ai).
3. Copy the token. It is a long string starting with `eyJ`.
4. In the app, open **Settings** and paste it into **comma JWT**. The app stores it with EncryptedSharedPreferences. You can paste either the raw token or a value that already starts with `JWT `.

## Dongle ID

The dongle ID is the 16-character id of the device (for example `4bba516fb4439b31`).

- In Settings, tap **List my devices**. The app calls `GET https://api.commadotai.com/v1/me/devices/` and you can tap a device to fill the id in. Each row shows whether Athena has pinged that device in the last two minutes.
- Or copy the id from the device page on connect.comma.ai and paste it into **Dongle ID**.

Both values are saved as you type.

## Use

The device must be awake and connected to comma. Nav alpha does not queue a destination for later; if the car is off, the app says the device is offline.

1. Long-press a favorite. Enter a name and either an address or coordinates.
   - Addresses are resolved with Android's Geocoder when you save, so the tap itself only sends coordinates.
   - Coordinates can be `32.87513, -117.20978`, `32.87513 -117.20978`, or a Google Maps link (`@lat,lon` or `q=lat,lon`).
2. Tap the favorite. The app sends it immediately and shows a success or error toast.
3. **Go** under **Address or lat, lon** sends a one-off destination the same way.
4. **Clear destination** calls `clearNavDestination`.

An empty favorite tells you to long-press it. Clearing the address field and saving removes that favorite's coordinates.

## What it calls

Confirmed against openpilot `system/athena/athenad.py` at `4c362ca` and the [comma API docs](https://api.comma.ai/) (Athena HTTP, and `openpilot/tools/lib/api.py` for the `Authorization: JWT` header and `https://api.commadotai.com` host):

```bash
curl -X POST "https://athena.comma.ai/$DONGLE_ID" \
  -H "Authorization: JWT $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"setNavDestination","params":{"latitude":32.875134,"longitude":-117.209777},"jsonrpc":"2.0","id":0}'
```

`setNavDestination(latitude, longitude)` writes the `NavDestination` param. Coordinates outside `latitude [-90, 90]` and `longitude [-180, 180]` are rejected. `clearNavDestination()` takes no params and removes that param.

The published OpenAPI schema types `setNavDestination` params as the older `NavigationDestination` object (`place_name`, `place_details`, latitude, longitude). The device method does not accept those extra fields, so this app sends only `latitude` and `longitude`.

A device that is not connected comes back as an offline error (the Athena docs also use the phrase `Device offline, message queued` for methods that can wait). This app says so directly. A build without nav alpha comes back as method-not-found.

## Release

Pushing a tag named `v*` (for example `v1.0.0`) builds the same debug APK and attaches it to a GitHub Release. Day-to-day builds upload the APK as the `op-navigation-debug` workflow artifact.

## Debug keystore

`keystore/debug.keystore` is committed on purpose so every debug APK has the same signature.

| | |
| --- | --- |
| Alias | `androiddebugkey` |
| Store password | `android` |
| Key password | `android` |

It is a shared debug key, not a Play Store signing key. Anyone with the repo can sign an update that installs over this app.
