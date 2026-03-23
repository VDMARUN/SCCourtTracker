# SC Court Tracker — Android App

## Building the APK

### Method 1: GitHub Actions (Recommended — no local setup needed)
1. Create a free account at github.com
2. Create a new repository called `SCCourtTracker`
3. Upload all these files to the repository
4. Go to **Actions** tab → **Build APK** → **Run workflow**
5. Wait ~5 minutes → Download APK from **Artifacts**
6. Transfer APK to phone → Install

### Method 2: Android Studio locally
1. Open Android Studio → Open this folder
2. Build → Generate Signed APK (or just Run for debug)

## Features
- Live scraping from cdb.sci.gov.in (no CORS issue — native HTTP)
- Pace calculation & ETA prediction
- Sequence-aware ETA (accounts for pass-over items)
- Lunch break presets (Till 2:10 PM / 30min / 1hr / custom)
- Status notifications every 5/10/15/30 minutes
- Rush alert when your item is 3 away
- Auto-sync every 1/2/5 minutes
- Persistent storage — remembers everything
