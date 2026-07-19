# Contributing to DNS Toggle

Thank you for your interest in contributing!

At its core, DNS Toggle is meant to focus on simplifying the process of toggling the device's Private DNS settings through the Quick Settings panel and simple, battery efficient, automation.  
If you wish to contribute to the project, please do your best to follow the guidelines below.

## App Intent & Design Philosophy

- **UI/UX:**  
  Always try to use the latest Android UI standards (Material 3). Keep the interface clean, expressive, and intuitive.
- **Performance & Battery:**  
  Try to ensure that any background automation remains highly optimized and keep the impact on the user's experience or device battery life minimal.  
  This means avoiding unnecessary wakelocks, polling, etc.
- **Privacy:**  
  The app must remain a simple shortcut and automation system for existing system settings.  
  No telemetry, data collection, analytics, or remote logging should ever be introduced. All permission-related activities must occur strictly on-device.
- **Dependencies:**  
  Try to avoid adding unnecessary dependencies. Only use them when they are absolutely necessary for functionality and follow standard Android development best practices.
- **F-Droid and IzzyOnDroid compliance:**  
  The app should always remain compliant with [F-Droid](https://f-droid.org/docs/Inclusion_Policy/) and [IzzyOnDroid](https://izzyondroid.org/docs/general/AppInclusionPolicy/) policies, meaning no proprietary dependencies, hardcoded API keys, or other non-free components should ever be introduced.

## Development Setup

First clone the repository to your local machine.

> [!NOTE]
>
> Using [Android Studio](https://developer.android.com/studio) is highly recommended to help ensure all contributed code follows the same style and formatting.

Once the project is open, you can build the app by running `./gradlew assembleDebug` via your preferred command line tool, or using Android Studio's built-in build tools.

## Coding Guidelines

- **Let the code speak:**  
  Aim for explicit, unabbreviated variable and function names.
- **Meaningful comments:**  
  Avoid redundant or purely descriptive comments. Only write comments when the code's underlying _intent_ is unclear or a complex workaround is necessary.
- **Localization:**  
  Ensure all user-facing strings are extracted to `strings.xml` and are fully translatable. Only standard development logs should be hardcoded in English.

## Pull Request Workflow

Pull requests should ideally be designed to target the `dev` branch.  
Regardless, a GitHub workflow is in place that will automatically re-route your PR into a temporary integration branch for review and testing.

## Translations

[![Translation status](https://hosted.weblate.org/widget/dns-toggle/multi-red.svg)](https://hosted.weblate.org/engage/dns-toggle/)

Want to see DNS Toggle in your native language? You don't need any coding experience to help out!

- **Weblate (preferred):**  
  You can translate directly from your browser using our [Weblate project](https://hosted.weblate.org/engage/dns-toggle/).
- **GitHub PR:**  
  Alternatively, you can submit standard Pull Requests modifying the XML string resources.
