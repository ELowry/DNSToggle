[![License: MIT](https://img.shields.io/badge/License-MIT-3d383b.svg)](LICENSE) [![GitHub release (latest by date)](https://img.shields.io/github/v/release/ELowry/DNSToggle?logo=GitHub&color=e29186)](https://github.com/ELowry/DNSToggle/releases/latest) <!--[![F-Droid](https://img.shields.io/f-droid/v/com.ericlowry.dnstoggle?logo=FDroid)](https://f-droid.org/packages/com.ericlowry.dnstoggle/) -->

# [![DNS Toggle](fastlane/metadata/android/en-US/images/featureGraphic.png)](#)

A tiny Android app that allows you to easily toggle your phone's Private DNS through the Quick Settings panel.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screenshot of Private DNS being toggled on in the Quick Settings menu" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Screenshot of Private DNS being toggled off in the Quick Settings menu" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screenshot of the DNS Toggle configuration menu" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Screenshot of the DNS Toggle configuration menu's rename popup" width="24%" />
</p>

> [!WARNING]
>
> To modify the Private DNS system settings, this app requires the `WRITE_SECURE_SETTINGS` permission. Since this is a protected system permission, you must either have a rooted device or manually grant the permission using [ADB](https://developer.android.com/tools/adb).
>
> If your device is not rooted, you can grant the required permission by connecting your phone to a computer with USB debugging enabled and running the following ADB command:
>
> ```bash
> adb shell pm grant com.ericlowry.dnstoggle android.permission.WRITE_SECURE_SETTINGS
> ```

## Features

### Quick Settings Tile

Adds a Quick Settings tile to toggle your Private DNS on and off with a single tap from your notification shade.

#### Additional Options

By long-pressing the Quick Settings tile, you can access the configuration menu to:

- **Custom DNS Provider**: Set a custom hostname for Private DNS (e.g., `dns.adguard.com`).
- **Wi-Fi Blocklist**: Automatically disable Private DNS when connected to specific Wi-Fi networks.
- **Auto-Update Blocklist**: Optionally add or remove the current SSID from the blocklist automatically when manually toggling the Quick Settings tile.
- **Status Notifications**: Receive notifications when Private DNS settings are automatically adjusted.
- **Dynamic Tile Labeling**: Rename the Quick Settings tile label via the settings menu.
- **Hide App Icon**: Optionally hide the app from your launcher drawer to keep your home screen clean.
- **Disable DNS Test**: Optionally bypass the automatic reachability check if you are using a strict custom DNS provider.

## Usage

1. Install the app on your Android device.
2. Grant the `WRITE_SECURE_SETTINGS` permission using root (you will be prompted when using the app) or the ADB command provided above.
3. Edit your Quick Settings panel and drag the **DNS Toggle** tile into your active tiles.
4. Long-press the tile to open the configuration UI to set your custom DNS hostname.
5. Tap the tile to toggle the Private DNS on or off!

## Troubleshooting

- **ADB Command Fails**: Ensure USB Debugging is enabled in Developer Options, and that your device is recognized by running the `adb devices` command.
- **Tile is Grayed Out**: This usually means the permission was not granted correctly. See the warning above.
- **Custom DNS is marked as "Unreachable"**: Some strict DNS providers block automatic connection tests. If you are certain the address is correct, you can toggle on the `Disable DNS Test` just option below.

## Permissions

- **WRITE_SECURE_SETTINGS**: Required to modify system Private DNS settings. Must be granted via root or using ADB.
- **INTERNET** *(optional)*: Used to verify that your Custom DNS Provider is online and reachable before applying it.
- **Location & Nearby Devices** *(optional)*: Required only for **Wi-Fi Blocklist** automation. Used to identify the Wi-Fi network name (SSID) locally.
- **Notifications** *(optional)*: Used for status alerts when Private DNS is automatically adjusted.

## Privacy

This app is a shortcut for existing system settings. It does not store, collect, or share any personal data. All permission-related activities (such as reading the Wi-Fi SSID) occur strictly on-device for automation purposes.

## Building from Source

You can build the app yourself by opening this project in Android Studio or by running `./gradlew assembleDebug` using your prefered command line tool.
