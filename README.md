# Interior Light (CorsaLight)

Ambijentalna LED rasvjeta i originalni **TID** display (Triple Info Display) za **Opel Corsa C**, upravljani s Android headunita preko USB-a i Arduina.

*Ambient LED lighting and the original Opel TID display (Corsa C / Astra G, 10 characters), driven from an Android head unit over USB through an Arduino.*

```
Android headunit  --USB-->  Arduino Uno/Nano  --D4-------->  WS2812B LED traka (26 LED)
 aplikacija Interior Light                    --D7/D5/D6-->  TID (SDA / SCL / MRQ)
```

## Što radi
- **LED traka:** 8 boja + vlastita boja, svjetlina, upali/ugasi, efekt mirna boja ili shooting star
- **TID:** pjesma i izvođač (dugi tekst se vrti), `VOL 15` s bar-grafom kad se mijenja glasnoća, poruke (`LED RED`…), vlastiti tekst kad ništa ne svira (`OPEL CORSA`)
- **Početna animacija** kod paljenja, može se birati:
  - TID: pojavi se, uklizi, prolazi, slovo po slovo, iz sredine, treptanje
  - LED: s rubova prema sredini, lagano paljenje, klizi s lijeva, iskrice
- Traka reagira na glasnoću i novu pjesmu
- Arduino pamti postavke u EEPROM-u, pa svjetlo radi i dok aplikacija nije spojena
- Aplikacija radi u pozadini i sama se spaja kad se Arduino uključi

Testirano na headunitu **TS18 (Dofun, Android 10)** i originalnom 10-znamenkastom TID-u Corse C.

## Spajanje

TID ima 12-pinski konektor sa stražnje strane:

| TID pin | Funkcija | Spoj |
|---|---|---|
| 1 | +12 V kontakt (15) | auto |
| 2 | AA, "radio upaljen" | +12 V (može preko 1 kΩ s kontakta), inače TID prikazuje datum |
| 3 | +12 V trajno (30) | auto |
| 6 | masa | **i na GND Arduina** |
| 10 | SCL | Arduino D5 |
| 11 | SDA | Arduino D7 |
| 12 | MRQ | Arduino D6 |

LED traka WS2812B: podaci na **D4**. Za punu svjetlinu traci treba vlastito napajanje od 5 V (step-down 12 V → 5 V, ≥ 3 A), s **zajedničkom masom** s Arduinom. Preko USB-a headunita traka je ograničena na oko 450 mA.

## Instalacija

### Arduino
1. Otvori `arduino/CorsaLight/CorsaLight.ino` u Arduino IDE-u
2. Instaliraj biblioteku **FastLED** (Library Manager)
3. Board: Arduino Uno ili Nano, pa Upload

### Headunit
1. Instaliraj APK iz [Releases](../../releases)
2. U aplikaciji otvori **⚙ Postavke**:
   - **Čitanje medija**: uključi *Interior Light* (za pjesmu na TID-u)
   - **Čitanje glasnoće**: u Pristupačnosti uključi *Interior Light – glasnoća na TID*, pa jednom promijeni glasnoću da je aplikacija nauči
3. Uključi Arduino i na pitanje Androida odaberi *Interior Light* uz "Uvijek"

**Napomena za TS18/Dofun:** kod potpunog gašenja headunit isključi dozvolu za pristupačnost, pa je nakon toga treba ponovno uključiti. CarPlay (ZLink) i Dofun radio ne javljaju Androidu naziv pjesme ni stanice.

## Dijagnostika
⚙ Postavke → **Dijagnostika** pokazuje media sesije, obavijesti, glasnoću i poruke s Arduina. Gumb **Test Arduina i TID-a** javlja stanje TID linija i greške pariteta.

Ako TID pokazuje prazno ispod sata, a datum nestane, provjeri **masu i kontakte**: TID dobiva podatke s greškom pariteta i zato prikazuje razmake. Ako TID uopće ne odgovara, izvuci mu konektor na 2 minute. Kad se prijenos prekine usred poruke, TID se zaglavi, a zbog trajnog napajanja ga ne resetira ni gašenje kontakta.

## Protokol (USB serial 9600, naredba + `\n`)
| Naredba | Značenje |
|---|---|
| `C255,0,0` | boja R,G,B |
| `B0-255` | svjetlina |
| `P0` / `P1` | ugasi / upali |
| `M0` / `M1` | mirna boja / shooting star |
| `R0` / `R1` | LED reagira na glasnoću i novu pjesmu |
| `V12,40,1` | glasnoća 12 od 40, `1` = piši na TID |
| `N<tekst>` | pjesma na TID (prazno = idle tekst) |
| `X` | bljesak za novu pjesmu |
| `L<tekst>` | poruka 2 s |
| `I<tekst>` | idle tekst |
| `E<tekst>` / `F<tekst>` | pozdrav / ime za početnu animaciju |
| `A0`–`A5` | stil pozdrava na TID-u |
| `K0`–`K3` | stil LED pozdrava |
| `W` | pokreni početnu animaciju |
| `D` / `T` | dijagnostika / test TID linija |
| `Z` | pauza slanja na TID 5 s (pošalji prije snimanja novog koda) |
| `?` | odgovor `OK CorsaLight 2` |

## Struktura
- `android/`: Android aplikacija (Kotlin, Gradle, minSdk 21)
- `arduino/CorsaLight/`: glavni Arduino program i TID driver (`TID10.h/.cpp`)
- `arduino/tools/`: testni programi (TID test, test pinova, reset EEPROM-a)

## Build
- Android: u mapi `android/` pokreni `gradlew assembleDebug`
- Arduino: `arduino-cli compile --fqbn arduino:avr:uno arduino/CorsaLight`

## Zasluge
- TID driver nastao iz biblioteke **TID12_10** (Arduino library for Opel TID, 10 digit)
- Paritet: Claas Anders "CaScAdE" Rathje, [mylifesucks.de](http://www.mylifesucks.de/oss/c-tid/)
- Opis protokola: *Protokoll TID* s [carluccio.de](http://www.carluccio.de)
- [opelforum.hu](https://www.opelforum.hu/forums/topic/2102-fed%C3%A9lzeti-computer-automenedzsment-%C3%A9p%C3%ADtett-megold%C3%A1sok-extr%C3%A1k/): spajanje TID-a i pin 2 (AA)

## Licenca
MIT, vidi [LICENSE](LICENSE).
