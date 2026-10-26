# Campus Event & Society Hub — Room + Firebase

Kotlin + Jetpack Compose Android app for the University of Malawi assessment. Room is the local cache/offline store; Firebase Authentication manages accounts, Cloud Firestore shares events/applications/registrations, and callable Cloud Functions enforce administrator-only approval and account management.

## What is included
- Student registration and sign-in through Firebase Authentication (email/password).
- Student profile and local Room cache.
- Shared published events in Cloud Firestore; event snapshots are copied into Room.
- Organiser applications submitted as `PENDING`; users cannot edit their own application status.
- Approval/rejection through a trusted callable Cloud Function. The function updates the user profile and Firebase custom claims.
- Admin-only account activation/deactivation through a trusted callable function.
- Firestore security rules and JUnit/Room test source files.
- A Room-only fallback remains available until `google-services.json` is added.

## 1. Create and connect Firebase
1. Open https://console.firebase.google.com/ and create a Firebase project.
2. Add an Android app with package name exactly `com.example.campuseventsocietyhub`.
3. Download `google-services.json` and place it at `app/google-services.json` (inside the `app` module, not the project root).
4. In Firebase Console → Authentication → Sign-in method, enable **Email/Password**.
5. Create a Cloud Firestore database.
6. From the project root, deploy the rules and functions (steps below).
7. Open the project in Android Studio and Sync Gradle.

Until `app/google-services.json` exists, the project intentionally uses its Room-only demo path. Do not share your Firebase service-account key or put it inside the Android app.

## 2. Deploy secure approval functions and Firestore rules
Install Node.js 20+ and Firebase CLI, then sign in:

```bash
npm install -g firebase-tools
firebase login
firebase use --add
cd firebase/functions
npm install
cd ../..
firebase deploy --only firestore:rules,functions
```

If Firebase CLI asks for a project, select the same project connected to the Android app. Cloud Functions deployment may require a billing-enabled Google Cloud project depending on current Firebase plan/region.

## 3. Create the pre-approved administrator
The app does not let users choose the administrator role during registration. Create the first admin using the Admin SDK script; this sets a trusted Firebase custom claim.

1. In Firebase Console → Project settings → Service accounts, generate a private key.
2. Save it as `firebase/scripts/serviceAccountKey.json`. **Never commit or share this file.**
3. In a terminal:

Windows PowerShell:
```powershell
cd firebase/scripts
npm install
$env:ADMIN_EMAIL="admin@campus.local"
$env:ADMIN_PASSWORD="Choose-A-Strong-Password-Here"
node create-admin.js
```

Use a strong password of at least 12 characters and remember it for the demo. The script creates the administrator profile and four shared sample events. The old Room-only demo password `Admin12345!` is not used when Firebase is configured.

## 4. Test the workflow
1. Sign in as the administrator.
2. Register a student account on a second device (or sign out and register on the same device).
3. Sign in as the student and submit **Apply to become an organiser**.
4. Sign in as the administrator and approve/reject the application in Admin.
5. Sign out and sign in again as the approved organiser so the refreshed Firebase token contains the organiser claim.
6. Create an event and check that it appears on other signed-in devices.
7. Register as a student and verify the registration persists in Room and Firestore.

## Data responsibilities
- **Firebase Authentication:** email/password identity and sign-in session.
- **Cloud Firestore:** shared user profile, role metadata, published events, organiser applications, registrations.
- **Room:** local cache for event lists, accounts visible to the current role, applications and registrations. Room helps local display but is not the authority for remote permissions.
- **Firestore Rules + Cloud Functions:** enforce server-side permissions. Do not replace these with client-side role checks alone.

## Testing
- Local tests: `./gradlew test` (if the Gradle wrapper is present; otherwise run tests through Android Studio).
- Room device tests: `./gradlew connectedAndroidTest` with a connected device/emulator.
- Use Firebase Emulator Suite for rules/functions tests before a production deployment.

## Important notes
- This is an assessment-oriented starter integration. You must add your own `google-services.json`, create the Firebase project, deploy the rules/functions, and create the admin account before cloud features can work.
- The source ZIP does not include Firebase credentials or your project-specific config.
- Firestore listener sync caches newly observed records into Room; it is not a full bidirectional conflict-resolution engine. Deletions made remotely may remain in the local cache until separately reconciled.
- For a production deployment, add email verification, rate limiting, App Check, proper privacy/retention handling, and automated Firebase Rules/Functions tests.
