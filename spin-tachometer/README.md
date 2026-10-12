# Spin Tachometer for Android
Camera-based optical tachometer with a glowing, customizable LED-style RPM display.

## Use
1. Install the APK and grant camera permission.
2. Put **one** visually contrasting spot/tape on a spinning object.
3. Aim the center reticle where the spot passes with every rotation.
4. Select DARK MARK for a dark spot or BRIGHT MARK for a reflective/light spot.
5. The display reports estimated rotations per minute with 3 decimal places.
6. Use the colored swatches or hue slider to customize the LED digits. PAUSE and RESET are included.

## Important limitations
Three decimal places are display formatting, **not** assurance of 0.001 RPM accuracy.
Camera frame rate, timing, exposure, rolling shutter, illumination, blur, and mark size
limit what can be measured. Start with low/moderate speeds and stable light. At 30 FPS,
fast rotation and multiple marks may alias and report an incorrect RPM. Use a purpose-built
optical/laser tachometer for high-precision or high-speed measurements.
Do not position the phone or your hands near moving equipment.

## Build
GitHub Actions: run the "Build Spin Tachometer APK" workflow or push under
`spin-tachometer/`. Download the `SpinTachometer-APK` artifact and unzip to get
`app-debug.apk`. No Termux or runtime scripting required. Android 8.0+.

## v2: TURNTABLE / PHONE ON PLATTER
Tap **TURNTABLE MODE • PHONE ON PLATTER** on the main screen.
Uses the Android **gyroscope** to measure RPM without looking through the camera.
- Place phone *screen up* close to the spindle with the platter **stopped** on a secure,
  non-slip pad (not on a vinyl record; keep clear of the tonearm).
- Tap **ZERO • STILL**; keep stationary for two seconds to subtract gyro bias.
- Start the turntable. The big LED display shows estimated **RPM** to three decimals
  (for example **33.333**, **45.000**, or **78.000**).
- **STABLE DIGITS** counter-rotates the numbers while the phone spins, for readability.
- Select display colors from six presets or a custom color slider.
- Press **CAMERA MODE** to return to the original optical tachometer.

A physical gyroscope is required, and its maximum angular rate may limit 45/78 RPM
on some devices. Three decimal places are only display formatting, NOT an accuracy claim.
Keep the phone secured; phone mass may affect turntable speed.

## v3: ALWAYS-UPRIGHT RPM GAUGE
- The **UPRIGHT GAUGE: ON** option is enabled by default.
- The entire illuminated circular dial, digits, and direction indicator counter-rotate as the phone spins, keeping the speed oriented toward a stationary viewer.
- Fast gyro samples update the dial orientation even between RPM number changes.
- Tap **UPRIGHT GAUGE** to toggle it. First ZERO with the platter stopped, while the screen faces the direction you want the RPM to face.
- Intended for phones placed face-up on a turntable, spinning around their screen-normal axis.
- Gyroscope integration can drift; the upright display is approximate and is not camera-based stabilization.
- Keep the phone secured and avoid contact with records or the tonearm.
