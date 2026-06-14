# NexusBrowser - Android App

Fast, Secure, Smart Web Browser built with Kotlin & Jetpack Compose.

## 🔧 What Was Fixed

- ✅ Fixed Compose type mismatches in `BottomNavItem` function
- ✅ Added missing `ripple` import from `androidx.compose.material.ripple`
- ✅ Corrected `MaterialIcon` type reference to proper `ImageVector`
- ✅ Added GitHub Actions CI/CD workflow for automatic APK builds

## 📋 Requirements

- Android SDK 34
- JDK 17 or higher
- Gradle 8.x

## 🚀 Quick Start

### Local Build

```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/NexusBrowser2.git
cd NexusBrowser2

# Grant execute permission to gradlew
chmod +x gradlew

# Build Debug APK
./gradlew assembleDebug

# Built APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

### Build Release APK

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## 🐙 GitHub Setup

1. Create a new GitHub repository
2. Push this code:
   ```bash
   git remote add origin https://github.com/YOUR_USERNAME/NexusBrowser2.git
   git branch -M main
   git push -u origin main
   ```

3. GitHub Actions will automatically build the APK on each push
4. Download built APK from Actions → Artifacts

## 📦 Project Structure

```
NexusBrowser2/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/nexus/browser/
│   │       │   ├── MainActivity.kt          (Main UI & Compose)
│   │       │   ├── AdBlocker.kt
│   │       │   ├── BookmarksHelper.kt
│   │       │   ├── DownloadHelper.kt
│   │       │   └── ... (other utilities)
│   │       └── res/
│   │           ├── drawable/
│   │           ├── layout/
│   │           ├── values/
│   │           └── ... (resources)
│   └── build.gradle.kts                    (Dependencies & Config)
├── build.gradle.kts                        (Project Config)
├── settings.gradle.kts
├── gradlew                                 (Gradle Wrapper)
└── .github/
    └── workflows/
        └── android-build.yml               (CI/CD Configuration)
```

## 🛠️ Dependencies

- **Jetpack Compose**: 1.6.1 (UI Framework)
- **Material3**: 1.1.2 (Material Design 3)
- **AndroidX Core**: 1.12.0
- **Activity Compose**: 1.8.1
- **Lifecycle**: 2.6.2

## ✨ Features

- Modern Material Design 3 UI
- Jetpack Compose based interface
- Multi-tab browsing
- File management
- Profile/Settings section
- Dark mode support
- Responsive UI with animations

## 📝 Build Configuration

- **Compile SDK**: 34
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Language**: Kotlin
- **Compose Compiler**: 1.5.15

## 🔨 Troubleshooting

### Build Error: "Compilation error. See log for more details"
- Ensure JDK 17 is installed
- Run: `./gradlew clean`
- Try: `./gradlew build --refresh-dependencies`

### Gradle Sync Issues
- Invalidate caches: `File → Invalidate Caches/Restart`
- Update Android Studio to latest version
- Check internet connection for dependency download

## 📄 License

Open source project - Feel free to use and modify!

## 🤝 Contributing

1. Fork the repository
2. Create feature branch
3. Commit your changes
4. Push and create Pull Request

---

**Built with ❤️ using Kotlin & Jetpack Compose**
