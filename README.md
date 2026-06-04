# Drop - Secure File Sharing

Drop is a secure, peer-to-peer file sharing application for Android and iOS that allows you to transfer files between devices over the internet.

## Features

- 🔒 **Secure & Private**: End-to-end encryption for all transfers
- 📱 **Easy to Use**: Simple interface with QR code and link sharing
- ⚡ **Fast & Reliable**: High-speed internet transfers
- 🎨 **Customizable**: Profile management with custom avatars
- 📊 **Transfer History**: Track all your file transfers
- 🌐 **Internet-based**: Works anywhere with internet connection

## Getting Started

### Prerequisites

- Android Studio Arctic Fox or later
- JDK 11 or later
- Android SDK API 29 or later

### Building the App

1. Clone the repository:
```bash
git clone https://github.com/your-username/drop-android.git
cd drop-android
```

2. Open the project in Android Studio

3. Build and run:
```bash
./gradlew assembleDebug
```

### Release Build

To create a release build:

```bash
./gradlew assembleRelease
```

## CI, Firebase, And Release Builds

GitHub Actions uses two environments for both platforms:

- `Development`: PR builds against the test Firebase project, `ark-drop-test`.
- `Production`: `v*` tag builds against the production Firebase project, `ark-drop-prod`.

### Development Workflows

- `android-dev.yml`: runs on pull requests to `main`, builds a signed Android release APK with the development Firebase config, runs KtLint and Android lint.
- `ios-dev.yml`: runs on pull requests to `main`, builds iOS and uploads to TestFlight with the development Firebase config.

### Production Workflows

Create a production tag to run both production workflows:

```bash
git tag v1.0.0
git push origin v1.0.0
```

- `android-prod.yml`: builds a signed Android release APK using the `Production` environment.
- `ios-prod.yml`: builds iOS with the `Production` environment and uploads to TestFlight.

### GitHub Secrets Checklist

Repository secrets shared by iOS TestFlight workflows:

- [ ] `ASC_API_KEY_BASE64`: base64-encoded App Store Connect API private key `.p8`.
- [ ] `ASC_ISSUER_ID`: App Store Connect API issuer ID.
- [ ] `ASC_KEY_ID`: App Store Connect API key ID.
- [ ] `IOS_P12_BASE64`: base64-encoded Apple Distribution certificate `.p12`.
- [ ] `IOS_P12_PASSWORD`: password for `IOS_P12_BASE64`.
- [ ] `IOS_PROFILE_BASE64`: base64-encoded App Store provisioning profile `.mobileprovision`.

For the current single Apple Developer account / single App Store app setup, these iOS signing and App Store Connect values are shared by both iOS workflows.

Firebase config secrets present in both `Development` and `Production`:

- [ ] `ANDROID_GOOGLE_SERVICES_JSON_BASE64`: base64-encoded Android `google-services.json`.
- [ ] `IOS_GOOGLE_SERVICE_INFO_PLIST_BASE64`: base64-encoded iOS `GoogleService-Info.plist`.

Use the same secret names in both environments. Only the secret values differ: `Development` values must come from `ark-drop-test`, and `Production` values must come from `ark-drop-prod`.

Android signing secrets present in both `Development` and `Production`:

- [ ] `ANDROID_KEYSTORE_ENCRYPTED`: encrypted ASCII-armored Android keystore, written to `keystore.asc` in CI.
- [ ] `ANDROID_KEYSTORE_PASSWORD`: passphrase used by GPG to decrypt `ANDROID_KEYSTORE_ENCRYPTED`.
- [ ] `ANDROID_KEYSTORE_STORE_PASSWORD`: password for the decrypted JKS file.
- [ ] `ANDROID_KEY_ALIAS`: alias of the signing key inside the JKS.
- [ ] `ANDROID_KEY_PASSWORD`: password for the signing key inside the JKS.

Both Android workflows validate that all signing secrets are present, that the encrypted keystore can be decrypted, that the alias exists, and that the key password can access the private key. Android development artifacts are not distributed through Google Play, but they use the same release build path with development environment secrets.

The workflow validates Firebase project IDs before building, so a development workflow must receive `ark-drop-test` configs and a production workflow must receive `ark-drop-prod` configs.

## How Drop Works

Drop uses peer-to-peer technology to transfer files directly between devices over the internet:

1. **Sender** selects files and starts transfer
2. **System** generates a secure transfer link and QR code
3. **Sender** shares the link or shows QR code to receiver
4. **Receiver** opens link or scans QR code to connect
5. **Files** are transferred directly between devices with encryption

## Sharing Options

Drop provides multiple ways to share transfers:

- **Deep Links**: Share via messaging apps, email, or any text-based communication
- **QR Codes**: Perfect for in-person sharing or when devices are nearby
- **Copy Link**: Quick clipboard copying for easy sharing

## Project Structure

```
app/
├── src/main/
│   ├── java/dev/arkbuilders/drop/app/
│   │   ├── ui/                 # Compose UI components
│   │   ├── data/               # Data layer
│   │   ├── domain/             # Business logic
│   │   └── di/                 # Dependency injection
│   ├── res/                    # Resources
│   └── AndroidManifest.xml
├── build.gradle.kts            # App build configuration
└── proguard-rules.pro          # ProGuard rules

fastlane/
└── metadata/android/en-US/     # Play Store metadata
    ├── title.txt
    ├── short_description.txt
    ├── full_description.txt
    └── changelogs/

.github/workflows/
├── build_apk.yml              # CI build workflow
└── release.yml                # Release workflow
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

For support and questions:
- Create an issue on GitHub
- Contact: support@arkbuilders.dev

## Privacy Policy

Drop respects your privacy:
- No data is collected or stored on external servers
- All transfers are direct device-to-device over internet
- Files are encrypted during transfer
- No analytics or tracking

For more details, see our [Privacy Policy](PRIVACY.md).
