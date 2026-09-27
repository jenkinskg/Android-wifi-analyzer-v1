GitHub upload package for jenkinskg/Android-wifi-analyzer-v1

Upload these two ZIP files to the ROOT of your GitHub repository:
- WiFiAnalyzerLauncher-AndroidStudio.zip
- WiFiAnalyzerStandalone-AndroidStudio.zip

Then create this file in GitHub:
.github/workflows/build-apks.yml

Use the contents of build-apks.yml included in this package.

After committing the files:
1. Open the Actions tab.
2. Choose "Build both APKs".
3. Click "Run workflow" if a build did not start automatically.
4. When the run finishes, download:
   - WiFiAnalyzerLauncher-debug-apk
   - WiFiAnalyzerStandalone-debug-apk

Each downloaded artifact contains app-debug.apk.
