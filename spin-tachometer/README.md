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
