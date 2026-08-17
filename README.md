[![License: MIT](https://img.shields.io/badge/License-MIT-3d383b.svg)](LICENSE) [![GitHub release (latest by date)](https://img.shields.io/github/v/release/ELowry/DNSToggle?logo=GitHub&color=a4785e)](https://github.com/ELowry/DNSToggle/releases/latest) [![IzzyOnDroid](https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/com.ericlowry.dnstoggle&label=IzzyOnDroid&color=e29186)](https://apt.izzysoft.de/fdroid/index/apk/com.ericlowry.dnstoggle) [![F-Droid](https://img.shields.io/f-droid/v/com.ericlowry.dnstoggle?logo=FDroid&color=e29186)](https://f-droid.org/packages/com.ericlowry.dnstoggle/) [![Translation status](https://hosted.weblate.org/widget/elowry/dnstoggle-strings/svg-badge.svg)](https://hosted.weblate.org/engage/elowry/)

# [![DNS Toggle](fastlane/metadata/android/en-US/images/featureGraphic.png)](#)

A tiny Android app that allows you to easily toggle your phone's Private DNS through the Quick Settings panel.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screenshot of the Private DNS hostname quick-switch popup" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Screenshot of Private DNS being toggled on in the Quick Settings menu" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screenshot of the DNS Toggle configuration menu" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Screenshot of the Wi-Fi profile configuration popup" width="24%" />
</p>

> [!WARNING]
>
> To modify the Private DNS system settings, this app requires the `WRITE_SECURE_SETTINGS` permission. Since this is a protected system permission, you must either have a rooted device, use [Shizuku](https://shizuku.rikka.app/) (no PC or root required after a one-time setup), or manually grant the permission using [ADB](https://developer.android.com/tools/adb).
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

- **Custom DNS Provider**:  
  Set and label one or more custom Private DNS hostnames you can easily toggle between (e.g., `dns.quad9.net`).
    - Optional automatic DNS hostname reachability check.
    - Automatically detects your active system DNS so you can easily save it to your list.
- **Wi-Fi Profiles**:  
  Automatically set or disable Private DNS when connected to specific Wi-Fi networks, including options to:
    - Auto-save changes to a Wi-Fi network profile when you manually toggle DNS or switch hostnames.
    - Automatically disable Private DNS for the current network if it causes connectivity issues, ensuring you never lose internet access.
- **VPN Override**:  
  Automatically set a specific DNS hostname or choose how Private DNS behaves (Automatic or fully Disabled) when a VPN connection is detected.
- **Select DNS behavior when toggled Off**:  
  Optionally choose between Android's default "Automatic" or "Off" Private DNS setting throughout the app.
- **Material You UI**:  
  Modern, dynamic interface that adapts to your system theme and accent colors (Android 12+).
- **Backup & Restore**:  
  Export and import your configuration via password-encrypted `.dnstoggle` files.
    > _Note: For security, your encrypted hostnames and Wi-Fi profiles are excluded from cloud backups and must be transferred manually._
- **Dynamic Tile Labeling**:  
  Rename the Quick Settings tile.
- **Hide App Icon**:  
  Optionally hide the app from your launcher drawer to keep your home screen clean.
- **USB Debugging Tile**:  
  (_Requires Developer Mode_) Tap the app version number at the bottom of the settings menu 5 times in quick succession to add a USB Debugging Quick Settings tile.
- **App Shortcuts**:  
  Includes app shortcuts to toggle Private DNS or open the quick-sepect/settings menu.

## Usage

1. Install the app on your Android device.
2. Grant the `WRITE_SECURE_SETTINGS` permission using root or Shizuku (you will be prompted when using the app), or the ADB command provided above.
3. Edit your Quick Settings panel and drag the **DNS Toggle** tile into your active tiles.
4. Long-press the tile to open a quick-select menu to easily switch between your saved DNS hostnames and enable or disable Private DNS.
5. Tap the tile to toggle the Private DNS on or off!

> [!NOTE]
>
> **Saved data may disappear**
>
> Android's secure Keystore protects your saved Wi-Fi networks and hostnames. If you change your device's lock screen PIN, pattern, or biometric settings, Android may delete the stored data.
>
> This encrypted data cannot be transferred to another device via the default Android cloud backup. Please use the app's built-in Export feature before migrating devices.

## Troubleshooting

If you encounter issues not covered here, please [open a support ticket](https://github.com/ELowry/DNSToggle/issues/new/choose).

- **ADB Command Fails**:  
  Ensure USB Debugging is enabled in Developer Options, and that your device is recognized by running the `adb devices` command.
- **Tile is Grayed Out**:  
  This usually means the permission was not granted correctly. See the warning above.
- **Custom DNS is marked as "Unreachable"**:  
  Some strict DNS providers block automatic connection tests. If you are certain the address is correct, you can enable the `Skip connection test` option.
- **"Unknown SSID" or Automation fails**:  
  - Ensure **Location Permissions** are set to "Allow all the time". Android requires this to identify Wi-Fi networks in the background.
  - Active **VPNs** or "Private DNS" settings on some devices can mask the SSID.
  - Some devices aggressively kill background apps. If automation stops working, you may need to disable `Battery Optimizations` or enable `Autostart` for DNS Toggle in your system settings.

## Permissions

- **WRITE_SECURE_SETTINGS**:  
  Required to modify system Private DNS settings. Must be granted via root, Shizuku, or using ADB.
- **INTERNET** _(optional)_:  
  Used to verify that your Custom DNS Provider is online and reachable before applying it.
- **Location & Nearby Devices** _(optional)_:  
  Required only for **Wi-Fi Profiles** automation. Used to identify the Wi-Fi network name (SSID) locally.
- **Notifications** _(optional)_:  
  Used for status alerts when Private DNS is automatically adjusted.

## Privacy

This app is a shortcut for existing system settings. It does not store, collect, or share any personal data. All permission-related activities (such as reading the Wi-Fi SSID) occur strictly on-device for automation purposes.

## Contributing & Translations

Contributions are always welcome! Whether you want to add a new feature, fix a bug, or help translate the app into your native language, please check out our [Contributing Guidelines](CONTRIBUTING.md) to get started.

### Translation Status

#### App Translation Status

[![App translation status](https://hosted.weblate.org/widget/elowry/dnstoggle-strings/multi-auto.svg)](https://hosted.weblate.org/engage/elowry/)

#### Metadata Translation Status

_This includes the app description and changelogs displayed on F-Droid and IzzyOnDroid._

[![Metadata translation status](https://hosted.weblate.org/widget/elowry/dnstoggle-metadata/multi-auto.svg)](https://hosted.weblate.org/engage/elowry/)

## License

This project is licensed under the [MIT License](LICENSE).
