# Work Package Report: WP-25a

## (a) Git Revisions & Diff Stat
- **Git HEAD Before:** `7f8314e3c4bd8130dfbc9863fc600996c71f7f69`
- **Git HEAD After:** (pending commit)
- **Git Diff Stat:**
```text
 docs/implementation/mesh/DECISIONS.md            | 16 ++++++++++++++++
 docs/implementation/mesh/DEFECT_REGISTER.md      | 45 +++++++++++++++++++++++++++++++++++++++++++++
 mobile-app/app/src/main/java/com/ridervoice/mesh/ui/OfflineRoomScreen.kt | 2 +-
 3 files changed, 62 insertions(+), 1 deletion(-)
```

## (b) Frozen Files Diff Output
```text
$ git diff --stat a37ef09 -- \
    mobile-app/app/src/main/java/com/ridervoice/network/LiveKitManager.kt \
    mobile-app/app/src/main/java/com/ridervoice/network/ConnectionState.kt \
    mobile-app/app/src/main/java/com/ridervoice/network/NetworkResilienceManager.kt \
    mobile-app/app/src/main/java/com/ridervoice/state/RoomViewModel.kt \
    mobile-app/app/src/main/java/com/ridervoice/state/ActiveRideRepository.kt \
    mobile-app/app/src/main/java/com/ridervoice/state/RoomFault.kt \
    mobile-app/app/src/main/java/com/ridervoice/state/AppEvents.kt \
    mobile-app/app/src/main/java/com/ridervoice/services/VoiceForegroundService.kt \
    mobile-app/app/src/main/java/com/ridervoice/audio \
    mobile-app/app/src/main/java/com/ridervoice/diagnostics \
    mobile-app/app/src/main/java/com/ridervoice/ui/screens/RoomScreen.kt \
    mobile-app/app/src/main/java/com/ridervoice/ui/screens/room \
    backend web-app livekit-server
(0 files changed - byte-identical to a37ef09)
```

## (c) Raw Command Outputs (Last 40 Lines)

### 1. `./gradlew testDebugUnitTest`
```text
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:bundleDebugClassesToRuntimeJar

> Task :app:hiltAggregateDepsDebugUnitTest UP-TO-DATE
WARNING: [Processor] Library 'D:\Projects\Rider_APP-main\mobile-app\app\build\intermediates\compile_app_classes_jar\debug\classes.jar' contains references to both AndroidX and old support library. This seems like the library is partially migrated. Jetifier will try to rewrite the library anyway.
 Example of androidX reference: 'androidx/compose/runtime/Composer'
 Example of support library reference: 'android/support/v4/media/session/MediaSessionCompat$Callback'

> Task :app:hiltJavaCompileDebugUnitTest NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:transformDebugUnitTestClassesWithAsm
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended

> Task :app:testDebugUnitTest

com.ridervoice.mesh.audit.VoiceSessionCoordinatorTest > fallbackToMeshRollsBackToLiveKitWhenMeshAudioFails FAILED
    java.lang.AssertionError at VoiceSessionCoordinatorTest.kt:134

com.ridervoice.mesh.audit.VoiceSessionCoordinatorTest > fallbackToMeshSucceedsWhenRouteExistsAndAudioEnables FAILED
    java.lang.AssertionError at VoiceSessionCoordinatorTest.kt:163

com.ridervoice.mesh.audit.VoiceSessionCoordinatorTest > recoveryToLiveKitRollsBackToMeshWhenLiveKitFailsToConnect FAILED
    java.lang.AssertionError at VoiceSessionCoordinatorTest.kt:206

307 tests completed, 3 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///D:/Projects/Rider_APP-main/mobile-app/app/build/reports/tests/testDebugUnitTest/index.html

BUILD FAILED in 53s
37 actionable tasks: 5 executed, 32 up-to-date
```

### 2. `./gradlew lintDebug`
```text
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:hiltAggregateDepsDebug UP-TO-DATE
> Task :app:hiltJavaCompileDebug UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:extractProguardFiles UP-TO-DATE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:generateDebugAndroidTestResValues UP-TO-DATE
> Task :app:generateDebugLintReportModel
> Task :app:lintAnalyzeDebug

> Task :app:lintReportDebug
Wrote HTML report to file:///D:/Projects/Rider_APP-main/mobile-app/app/build/reports/lint-results-debug.html

> Task :app:lintDebug

BUILD SUCCESSFUL in 1m 22s
31 actionable tasks: 5 executed, 26 up-to-date
```

### 3. `./gradlew assembleDebug`
```text
> Task :app:transformDebugClassesWithAsm UP-TO-DATE
> Task :app:dexBuilderDebug
> Task :app:mergeDebugGlobalSynthetics UP-TO-DATE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:mergeDebugJavaResource UP-TO-DATE
> Task :app:checkDebugDuplicateClasses UP-TO-DATE
> Task :app:mergeExtDexDebug UP-TO-DATE
> Task :app:mergeLibDexDebug UP-TO-DATE
> Task :app:mergeDebugJniLibFolders UP-TO-DATE
> Task :app:mergeDebugNativeLibs UP-TO-DATE
> Task :app:stripDebugDebugSymbols UP-TO-DATE
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 46s
45 actionable tasks: 4 executed, 41 up-to-date
```

### 4. `ALLOW_INSECURE_RELEASE_BUILD=true ./gradlew assembleRelease`
```text
> Task :app:compileReleaseJavaWithJavac
> Task :app:hiltAggregateDepsRelease UP-TO-DATE
> Task :app:hiltJavaCompileRelease
> Task :app:generateReleaseLintVitalReportModel
> Task :app:processReleaseJavaRes
> Task :app:mergeReleaseJavaResource
> Task :app:transformReleaseClassesWithAsm
> Task :app:mergeReleaseGeneratedProguardFiles UP-TO-DATE
> Task :app:expandReleaseArtProfileWildcards
> Task :app:lintVitalAnalyzeRelease
> Task :app:minifyReleaseWithR8
> Task :app:lintVitalReportRelease
> Task :app:lintVitalRelease
> Task :app:compileReleaseArtProfile
> Task :app:uploadCrashlyticsMappingFileRelease
> Task :app:shrinkReleaseRes
> Task :app:optimizeReleaseResources
> Task :app:packageRelease
> Task :app:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:assembleRelease

BUILD SUCCESSFUL in 5m 14s
58 actionable tasks: 26 executed, 32 up-to-date
```

## (d) JUnit XML Totals
- **Total Tests:** 307
- **Failures:** 3
- **Errors:** 0
- **Skipped:** 0

## (e) Defects Closed & Status
- **Closed:** N4 (Fixed compilation error at `OfflineRoomScreen.kt:151` by invoking `voxBridge?.setPttPressed(held)` without creating alias).
- **Opened / Logged Baseline Failures:**
  - `T-FAIL-1` (line 134 in `VoiceSessionCoordinatorTest`): coordinator rollback state mismatch on hardware failure.
  - `T-FAIL-2` (line 163 in `VoiceSessionCoordinatorTest`): test fake expects `stoppedCount == 1` but coordinator calls `pause()`.
  - `T-FAIL-3` (line 206 in `VoiceSessionCoordinatorTest`): test fake state remains `CONNECTED` across pause.
- Scheduled for resolution in WP-25c / WP-27 / WP-28.

