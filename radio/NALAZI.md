# Radio na TS18 – nalazi reverse engineeringa

Izvor: `TS18.2.2_20241016.104249_WINDOW-THEME1-V2.zip` (Desktop).
Radni materijal (raspakirano, dekompilirano): `C:\Users\leoju\radio-re\`.

## Firmware
| Datoteka u zipu | Što je |
|---|---|
| `183f244a-….0`–`.3` | spojeno = `super.img` (Android dynamic partitions, LP v10): `system` 2.5 GB + `vendor` 400 MB, oboje ext4, **nije šifrirano** |
| `188e7e2e-…` | ext4 s kernel modulima (`sprd_fm.ko`, `sprdwl_ng.ko`, …) |
| `1813ade8-…`, `182a385a-…` | šifrirano/nepoznato (vjerojatno bootloader/MCU) |

Alati: `radio-re/tools/lpdump.mjs` (super.img → particije), `radio-re/tools/ext4.mjs` (list/extract iz ext4).

## Radio aplikacija
- `/system/priv-app/com.tw.radio_78cc/com.tw.radio_78cc.apk`, paket `com.tw.radio`, `sharedUserId=android.uid.system`
- Platforma TopWay ("tw") s Eckom bibliotekom `com.eckom.xtlibrary.twproject`
- Teme su zasebni APK-ovi: `/system/etc/theme/<tema>/Sub/RadioTheme.apk`
- Logika: `twproject/radio/model/RadioModel.java`; UI: `com/tw/radio/RadioActivity.java`

## Kako priča s tunerom
```
RadioModel → TWRadio extends android.tw.john.TWUtil (framework.jar)
           → JNI u libandroid_runtime.so → /dev/tw (MCU)
```
- `/dev/tw`: `0666 root root` (ueventd.rc), SELinux label `tw_device`
- SELinux: `allow untrusted_app_29_0 tw_device (chr_file (ioctl read write open))`
  → **obična aplikacija (bez roota i sistemskog potpisa) može koristiti TWUtil**
- `TWUtil` je u boot classpathu, pa mu se pristupa refleksijom (ili compile-only stubom)

### API
```java
TWUtil tw = new TWUtil(1);          // class 1 = radio
tw.open(new short[]{265,266,274,513,515,769,1025,1026,1028,1029,1030,(short)0x9E00,(short)0x9E01}); // == 0 ok
tw.start();
tw.addHandler("tag", handler);      // događaji stižu kao Message(what, arg1, arg2, obj)
tw.write(what, arg1, arg2);
tw.stop(); tw.close();
```

### Naredbe (write)
| what | arg1 | arg2 | Značenje |
|---|---|---|---|
| 1025 | 0 | 0 | AS (auto store / skeniranje) |
| 1025 | 1 | 1 / 0 | seek prev / next |
| 1025 | 2 | 0 / 1 | korak gore / dolje |
| 1025 | 4 | 0/1 | LOC / DX |
| 1025 | 5 | band | band (0,1 = FM1/FM2, 2 = AM) |
| 1025 | 6 | ch | stereo/mono |
| 1025 | 8 | idx | spremi trenutnu na poziciju idx |
| 1025 | 9 | 0/1 | FM odašiljač |
| 1025 | 255 | – | zatraži stanje |
| 1026 | 255 | freq | postavi frekvenciju |
| 1026 | 0 | band | prijelaz na band (sendFM) |
| 1028 | 0/1/3 | 0/1 | RDS AF / TA / REG |
| 1028 | 4 | idx | odaberi stavku liste (PTY) |
| 1028 | 255 | – | zatraži RDS stanje |
| 1030 | 0 | – | zatraži regiju |
| 40465 | 192 | 1 / 129 | zatraži audio izvor radio / otpusti |
| 40448 | activity | – | radio aktivnost u prvom planu |
| 513 | 2 | 3 | mute toggle |
| 265, 266, 274, 769, 515 | 255 | – | zatraži lokaciju, MCU verziju, config, izvor, mute |

### Događaji (handler)
| what | Sadržaj |
|---|---|
| 1025 | arg1=0: zastavice arg2 (2 FM odašiljač, 4 mono, 8 LOC, 16 stereo, 128 skeniranje u tijeku); arg1=1: band=arg2; arg1=2: freq=arg2, obj=PS ime; arg1=3: PTY; arg1=4: indeks spremljene (−1 nijedna) |
| 1026 | spremljena stanica: arg1&255 = indeks (0–17), arg2 = freq, (arg1>>8)&255 = PTY, arg1>>16 = PI, obj = PS |
| 1028 | RDS zastavice (arg1>>16 = PI; 64 AF, 32 TA, 4 REG) |
| 1029 | RadioText (obj String) |
| 1030 | regija: arg1 0 = regija, 1/2 FM max/min, 3/4 AM max/min, 5 FM korak, 6 AM korak, 7 RDS dostupan, 8–10 FM2 |
| 769 | aktivni izvor (1 = radio) |
| 515 | mute (arg1 & 0x80000000) |
| 513 | tipke na volanu |
| 40449 | tipke: 3 next, 4 prev, 9 seek next, 10 seek prev |

18 spremljenih mjesta (`FreqPs[18]`).

## Otvoreno
- Ponašanje kad originalna radio aplikacija radi istovremeno (dva klijenta na /dev/tw)
- Jedinice frekvencije (vjerojatno FM u 10 kHz, npr. 8750 = 87.5 MHz) – potvrditi na uređaju
- Ostatak `onRdsDateChange`, PTY lista, logotipi stanica (`/sdcard/iNand/radio/radio_data.txt`)
