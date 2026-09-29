# WallShare

WallShare is a social Android application that lets you and your friends securely share and apply wallpapers to each other's devices. 

## Features

*   **Authentication:** Sign in via Google or create a traditional username/password account.
*   **Unique Usernames:** Claim a unique username (e.g., `@your_username`) to make it easy for friends to find you.
*   **Friend System:** Search for users, send friend requests, and manage your incoming/outgoing requests.
*   **Share Wallpapers:** Pick an image from your gallery and send it directly to a friend's device.
*   **Background Updates:** Wallpapers are applied silently in the background (Home screen, Lock screen, or both) using push notifications.

## Tech Stack

*   **UI:** Jetpack Compose, Material 3, custom rich dark theme.
*   **Language:** Kotlin.
*   **Backend:** [Supabase](https://supabase.com/) (Auth, PostgreSQL Database, Storage, Realtime).
*   **Push Notifications:** Firebase Cloud Messaging (FCM).
*   **Image Loading:** Coil.
*   **Background Tasks:** Android WorkManager.

## Prerequisites

To run this project, you will need a few external services set up:

1.  **Supabase Project:** 
    *   Set up a Supabase project.
    *   Enable Auth, Storage (for images), and Database.
    *   You will need the `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
2.  **Firebase Project:**
    *   Create a Firebase project for Cloud Messaging (FCM).
    *   Download the `google-services.json` file.
    *   Generate a Web Client ID for Google Sign-In.

## Getting Started

1.  **Clone the repository:**
    ```bash
    git clone <your-repo-url>
    cd WallShare
    ```

2.  **Configure Environment Variables:**
    Create a `local.properties` file in the root of the project (if it doesn't exist) and add your keys. Note: DO NOT commit this file to source control.
    ```properties
    SUPABASE_URL=your_supabase_url
    SUPABASE_ANON_KEY=your_supabase_anon_key
    GOOGLE_WEB_CLIENT_ID=your_google_web_client_id
    ```

3.  **Add Firebase config:**
    Place your `google-services.json` file into the `app/` directory.

4.  **Build and Run:**
    Open the project in Android Studio, sync the Gradle files, and hit run!

## License
MIT License
