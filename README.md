# Interior Light (CorsaLight)

Ambient LED lighting and the original **TID** (Triple Info Display) for the **Opel Corsa C**, controlled from an Android head unit over USB through an Arduino.

```
Android head unit  --USB-->  Arduino Uno/Nano  --D4-------->  WS2812B LED strip (26 LEDs)
 Interior Light app                           --D7/D5/D6-->  TID (SDA / SCL / MRQ)
```

## Features
- **LED strip:** six colours plus custom colours on the classic screen, brightness, on/off
- **Animations:** Static Colour, Shooting Star, Raindrops, Random, Calm, Smoke, Thunder, Changing Colour
- **TID:**
  - song and artist, with long text scrolling
  - `VOL 15` with a bar graph when the volume changes
  - short messages (`LED RED`…)
  - your own static text when nothing is playing (`OPEL CORSA`)
- **Welcome animation** when you switch on the ignition, with your own text and name:
  - TID: appear, slide in, pass through, letter by letter, from the centre, blink
  - LED: from the edges, soft fade-in, slide from the left, sparkles
- The strip reacts to the volume and to a new song
- The Arduino keeps its settings in EEPROM, so the light works even when the app isn't connected
- The app runs in the background and connects by itself when the Arduino is powered
- **The app updates the Arduino firmware by itself** when it carries a newer version (Advanced Settings → OAI firmware)

Tested on a **TS18 (Dofun, Android 10)** head unit and the original 10-digit Corsa C TID.

## App (3.0)
The 3.0 UI follows the Figma design *Corsa C Preferences*. Its colour follows the LED colour.

| Screen | What it holds |
|---|---|
| Ambient Lighting | Footwell colour, brightness and animation, with a live preview |
| Center Display | Welcome message and animation, static text, song and artist, volume, light change alerts |
| User Preferences | User name, personal welcome message |
| Car Setup | LED welcome, LED reacts to music, media and volume access, auto-update, classic screen |
| Advanced Settings | Connection status, OAI firmware update, OAI Debugging, Assist, Credits |

Long-press the Opel logo on the home screen to toggle demo mode, which works without an Arduino.

## Wiring

The TID has a 12-pin connector on the back:

| TID pin | Function | Connect to |
|---|---|---|
| 1 | +12 V ignition (15) | car |
| 2 | AA, "radio on" | +12 V (through 1 kΩ from ignition is fine), otherwise the TID shows the date |
| 3 | +12 V permanent (30) | car |
| 6 | ground | **also to Arduino GND** |
| 10 | SCL | Arduino D5 |
| 11 | SDA | Arduino D7 |
| 12 | MRQ | Arduino D6 |

WS2812B LED strip: data on **D4**.
- For full brightness, give the strip its own 5 V supply: a 12 V → 5 V step-down rated at least 3 A.
- The strip and the Arduino need a **common ground**.
- When powered from the head unit's USB, the strip is limited to about 450 mA.

## Installation

### Arduino
1. Open `arduino/CorsaLight/CorsaLight.ino` in the Arduino IDE.
2. Install the **FastLED** library (Library Manager).
3. Choose Arduino Uno or Nano as the board, then click Upload.

Later updates come from the app, so you only flash it by hand once.

### Head unit
1. Install the APK from [Releases](../../releases). 3.0 installs as an update over 2.9.
2. In the app, open **Car Setup**:
   - **Media access:** enable *Interior Light* (needed for the song on the TID).
   - **Volume reading:** in Accessibility, enable *Interior Light – volume on the TID*. Then change the volume once so the app can learn the volume pop-up.
3. Power the Arduino. When Android asks, choose *Interior Light* and tick "Always".

**TS18/Dofun notes:**
- After a full power-off, the head unit turns the accessibility permission off, so you have to enable it again.
- CarPlay (ZLink) and the Dofun radio don't report the song or the station to Android.

## Troubleshooting
**Advanced Settings → OAI Debugging** shows the log and messages from the Arduino. **Test** reports the state of the TID lines and parity errors.

If the TID goes blank under the clock and the date disappears, check the **ground and contacts**. The TID is receiving data with parity errors, so it shows spaces.

If the TID doesn't respond at all, unplug its connector for 2 minutes. When a transfer is cut off mid-message, the TID gets stuck. Because it has permanent power, turning the ignition off doesn't reset it.

## Protocol (USB serial 9600, command + `\n`)
| Command | Meaning |
|---|---|
| `C255,0,0` | colour R,G,B |
| `B0-255` | brightness |
| `P0` / `P1` | off / on |
| `M0`–`M7` | animation (0 static … 7 changing colour) |
| `R0` / `R1` | LED reacts to volume and new song |
| `V12,40,1` | volume 12 of 40, `1` = write to the TID |
| `N<text>` | song on the TID (empty = idle text) |
| `X` | flash for a new song |
| `L<text>` | 2 s message |
| `I<text>` | idle text |
| `E<text>` / `F<text>` | greeting / name for the welcome animation |
| `G0` / `G1` | welcome animation off / on |
| `A0`–`A5` | TID welcome style |
| `K0`–`K3` | LED welcome style |
| `Y` / `Y5000` | rainbow on the LED strip (easter egg); 5 s if no time is given |
| `W` | play the welcome animation |
| `D` / `T` | diagnostics / TID line test |
| `Z` | pause sending to the TID for 5 s (send before flashing new firmware) |
| `?` | replies `OK CorsaLight v31` (firmware version) |

## Structure
- `android/`: Android app (Kotlin, Gradle, minSdk 21); bundled firmware in `app/src/main/assets/firmware/`
- `arduino/CorsaLight/`: main Arduino program and TID driver (`TID10.h/.cpp`)
- `arduino/tools/`: test programs (TID test, pin test, TID recovery, EEPROM restore)
- `design/`: Figma assets and screenshots

## Build
- Android: run `gradlew assembleDebug` in `android/`.
- Arduino: `arduino-cli compile --fqbn arduino:avr:uno arduino/CorsaLight`.
- To bundle the firmware into the app, run `arduino/build_firmware.ps1`. It compiles the firmware and copies the hex and `version.txt` into the app's assets.

## Credits
- App, OAI module and TID: Leo Jukić
- Design: Andrija Pulić
- The TID driver is based on the **TID12_10** library (Arduino library for Opel TID, 10 digit).
- Parity: Claas Anders "CaScAdE" Rathje, [mylifesucks.de](http://www.mylifesucks.de/oss/c-tid/)
- Protocol description: *Protokoll TID* from [carluccio.de](http://www.carluccio.de)
- [opelforum.hu](https://www.opelforum.hu/forums/topic/2102-fed%C3%A9lzeti-computer-automenedzsment-%C3%A9p%C3%ADtett-megold%C3%A1sok-extr%C3%A1k/): TID wiring and pin 2 (AA)

## License
MIT, see [LICENSE](LICENSE).
