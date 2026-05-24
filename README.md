# Rider Voice

Rider Voice is a real-time communication system and mobile application tailored for motorcycle riders. It provides active voice communications, real-time route planning, and ride statistics. 

## Technical Stack
- Frontend: Kotlin / Jetpack Compose
- Audio & Real-time: LiveKit, WebRTC
- Backend: Node.js 
- State Management: ViewModels with StateFlow
- Dependency Injection: Dagger Hilt
- Maps: Mapbox SDK
- Authentication: Firebase Auth

## Core Features
- Live Intercom Communication: Real-time 3-way voice communication via WebRTC.
- Active Ride HUD: Dedicated dashboard for real-time ride tracking.
- Dynamic Route Planning: Origin and destination management with distance, duration, and elevation calculations.
- Ride Statistics: Real-time graphing and recording of speed, total distance, and average speeds. 
- Background Processing: Foreground service stability for uninterrupted communication during rides.
- Bluetooth Integration: Support for devices such as Cardo Packtalk.
- Authentication: Cloud-synced guest login and persistent sessions.

## Project Structure
- /mobile-app: Contains the Android application built with Kotlin and Jetpack Compose.
- /backend: Contains the Node.js signaling and management server.
- /livekit-server: Configurations for the LiveKit WebRTC server.

## Getting Started
1. Open the /mobile-app folder in Android Studio.
2. Ensure you have added your google-services.json file for Firebase Authentication support.
3. Sync Gradle and build the project.
4. Run the app on a physical Android device for accurate Bluetooth and WebRTC testing. 
