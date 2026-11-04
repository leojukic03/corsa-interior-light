/*
  CorsaLight 2 - ambijentalna LED rasvjeta + TID display za Opel Corsa C
  Arduino Nano, WS2812B traka na D4, TID na D7 (SDA), D5 (SCL), D6 (MRQ).
  Potrebna biblioteka: FastLED.

  Protokol s aplikacije (USB serial 9600, svaka naredba zavrsava s '\n'):
   C<r>,<g>,<b>         boja 0-255, npr. C255,0,0
   B<0-255>             svjetlina
   P<0|1>               ugasi / upali LED
   M<0|1>               0 = mirna boja, 1 = shooting star
   R<0|1>               LED reagira na glasnocu i novu pjesmu
   V<cur>,<max>[,<tid>] glasnoca promijenjena; tid=0 -> ne pisi VOL na TID
   N<tekst>             pjesma / stanica na TID-u (prazno = idle tekst)
   X                    nova pjesma -> bljesak po traci
   L<tekst>             poruka na TID-u 2 s (npr. LED RED)
   I<tekst>             idle tekst (default OPEL CORSA)
   E<tekst>             welcome tekst 1 (default WILLKOMMEN)
   F<tekst>             welcome tekst 2 (default LEO, prazno = preskoci)
   Z                    pauza TID-a 5 s (prije snimanja koda)
   A<0-5>               stil pozdrava na TID-u: 0 pojavi se, 1 uklizi, 2 prolazi, 3 slovo po slovo, 4 iz sredine, 5 treptanje
   K<0-3>               stil LED pozdrava: 0 s rubova, 1 lagano paljenje, 2 klizi s lijeva, 3 iskrice
   Y[ms]                duga na traci (easter egg), bez broja 5 s
   W                    ponovi welcome animaciju
   ?                    Arduino odgovori "OK CorsaLight v<FW_VERSION>"
   D                    dijagnostika (postavke, TID, brzina petlje)

  Boja, svjetlina, ukljuceno/iskljuceno, efekt i idle tekst spremaju se u
  EEPROM, pa svjetlo radi i dok aplikacija nije spojena.
*/
#include <FastLED.h>
#include <EEPROM.h>
#include "TID10.h"

#define LED_PIN  4
#define NUM_LEDS 26
#define BAUD     9600
#define LED_MAX_MA 450  // strujni limit za traku (USB napajanje)
// Verzija ovog koda. Aplikacija nosi istu verziju u sebi i sama snimi Arduino
// kad je njegova verzija starija (vidi android/.../Firmware.kt). Povecaj kod svake promjene!
#define FW_VERSION 31

TID10 tid(7, 5, 6);  // SDA -> D7, SCL -> D5, MRQ -> D6
CRGB leds[NUM_LEDS];

// ---------------- postavke (EEPROM) ----------------
struct Settings {
  uint8_t magic;
  uint8_t r, g, b;
  uint8_t bright;
  uint8_t power;
  uint8_t mode;
  uint8_t react;
  char idle[11];
  char welcome1[11];
  char welcome2[11];
  uint8_t welcomeAnim;  // stil pozdrava na TID-u (0-5, vidi welcomeFrame)
  uint8_t ledWelcome;   // stil LED pozdrava (0-3, vidi renderWelcome)
  uint8_t welcomeOn;    // 1 = pozdrav na TID-u kod paljenja (Center Display -> Welcome message)
};
const uint8_t MAGIC = 0x51;
// Stari program koristi EEPROM adrese 0 i 1 (boja, svjetlina). Nase postavke idu od 16,
// da se stari program ne pokvari ako ga ikad vratis.
const int EE_BASE = 16;
Settings cfg;
bool savePending = false;
unsigned long saveT = 0;

void loadSettings() {
  EEPROM.get(EE_BASE, cfg);
  if (cfg.magic != MAGIC) {
    cfg = Settings{MAGIC, 255, 0, 0, 26, 1, 0, 1, "OPEL CORSA", "WILLKOMMEN", "LEO", 2, 0, 1};
  }
  cfg.idle[10] = 0;
  cfg.welcome1[10] = 0;
  cfg.welcome2[10] = 0;
}

void saveLater() {
  savePending = true;
  saveT = millis();
}

void saveIfDue(unsigned long now) {
  // ceka 3 s nakon zadnje promjene, da slider ne trosi EEPROM
  if (savePending && now - saveT >= 3000) {
    EEPROM.put(EE_BASE, cfg);
    savePending = false;
  }
}

// ---------------- stanje ----------------
CRGB base = CRGB::Black;  // trenutna boja (polako ide prema ciljanoj)
uint8_t curBright = 0;
unsigned long lastFrame = 0;

bool welcomeLed = false, welcomeTid = false;
unsigned long welcomeT = 0;
// Pozdrav na TID-u broji od prvog primljenog okvira: TID-u treba par sekundi da se upali
unsigned long welcomeTidT = 0;
bool welcomeTidStarted = false;
uint32_t sparkleMask = 0;  // koje su LED-ice vec upaljene kod iskrica (26 bitova)
const uint16_t WIPE_MS = 1400;

uint8_t volCur = 0, volMax = 15;
bool volTid = true;
unsigned long volT = 0;  // 0 = nema volume efekta
const uint16_t VOL_HOLD = 1500, VOL_FADE = 500;

bool pulse = false;
unsigned long rainbowT = 0, rainbowMs = 0;  // easter egg: duga na traci (naredba Y)
unsigned long pulseT = 0;
const uint16_t PULSE_MS = 900;

CRGB star[NUM_LEDS];
uint8_t starPos = NUM_LEDS, starTail = 30, starStep = 20;
unsigned long starTick = 0, starNext = 0;

char nowText[65] = "";
uint8_t nowVersion = 0, shownVersion = 0;
char msgText[33] = "";
unsigned long msgT = 0;

char shown[10];
uint8_t shownBars = 255, srcId = 255, scrollPos = 0;
unsigned long scrollNext = 0, tidRetry = 0, tidLast = 0, tidSent = 0;

char line[72];
uint8_t lineLen = 0;

unsigned long tidPauseUntil = 0;
unsigned long loopCount = 0, loopRate = 0, loopT = 0;  // za naredbu D

// ---------------- pomocne ----------------
CRGB savedColor() { return CRGB(cfg.r, cfg.g, cfg.b); }
CRGB targetColor() { return cfg.power ? savedColor() : CRGB(CRGB::Black); }

uint8_t approach(uint8_t v, uint8_t t) {
  if (v == t) return v;
  int d = (int)t - v;
  int s = d / 8;
  if (s == 0) s = d > 0 ? 1 : -1;
  return v + s;
}

// TID zna samo velika slova, brojke i nekoliko znakova
void cleanText(char *s) {
  static const char ok[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'*+,-/<>_!\"#$%&().:;=?@[]^";
  for (; *s; s++) {
    char c = *s;
    if (c >= 'a' && c <= 'z') c -= 32;
    if (!strchr(ok, c)) c = ' ';
    *s = c;
  }
}

void copyText(char *dst, const char *src, uint8_t size) {
  strncpy(dst, src, size - 1);
  dst[size - 1] = 0;
  cleanText(dst);
}

int nextInt(char *&p) {
  while (*p == ',' || *p == ' ') p++;
  int v = atoi(p);
  while (*p && *p != ',') p++;
  return v;
}

// constrain() je makro i izvrsi argument vise puta, pa nextInt ide prvo u varijablu
uint8_t nextByte(char *&p) {
  int v = nextInt(p);
  return constrain(v, 0, 255);
}

void startWelcome(bool forceTid = false) {
  welcomeT = millis();
  welcomeLed = cfg.power;
  welcomeTid = cfg.welcomeOn || forceTid;  // "Welcome message" iskljucen: samo LED animacija
  welcomeTidStarted = false;
  sparkleMask = 0;
  base = CRGB::Black;
}

// ---------------- naredbe ----------------
void handleLine(char *s) {
  char *a = s + 1;
  switch (s[0]) {
    case 'C':
      cfg.r = nextByte(a);
      cfg.g = nextByte(a);
      cfg.b = nextByte(a);
      saveLater();
      break;
    case 'B':
      cfg.bright = constrain(atoi(a), 0, 255);
      saveLater();
      break;
    case 'P':
      cfg.power = atoi(a) ? 1 : 0;
      saveLater();
      break;
    case 'M':
      cfg.mode = constrain(atoi(a), 0, 7);
      memcpy(star, leds, sizeof(star));  // efekt krene od trenutnog stanja
      starPos = NUM_LEDS;
      saveLater();
      break;
    case 'R':
      cfg.react = atoi(a) ? 1 : 0;
      saveLater();
      break;
    case 'V': {
      volCur = nextByte(a);
      uint8_t m = nextByte(a);
      volMax = m > 0 ? m : 15;
      volTid = *a ? nextInt(a) != 0 : true;
      volT = millis() | 1;
      break;
    }
    case 'N':
      copyText(nowText, a, sizeof(nowText));
      nowVersion++;
      break;
    case 'X':
      pulse = true;
      pulseT = millis();
      break;
    case 'L':
      copyText(msgText, a, sizeof(msgText));
      msgT = millis() | 1;
      break;
    case 'I':
      copyText(cfg.idle, a, sizeof(cfg.idle));
      saveLater();
      break;
    case 'E':
      copyText(cfg.welcome1, a, sizeof(cfg.welcome1));
      saveLater();
      break;
    case 'F':
      copyText(cfg.welcome2, a, sizeof(cfg.welcome2));
      saveLater();
      break;
    case 'Z':
      // pauza TID-a 5 s (prije snimanja novog koda, da reset ne padne usred poruke)
      tidPauseUntil = millis() + 5000;
      Serial.println(F("Z TID pauza"));
      break;
    case 'A':
      cfg.welcomeAnim = constrain(atoi(a), 0, 5);
      saveLater();
      break;
    case 'G':
      cfg.welcomeOn = atoi(a) ? 1 : 0;
      saveLater();
      break;
    case 'K':
      cfg.ledWelcome = constrain(atoi(a), 0, 3);
      saveLater();
      break;
    case 'Y':  // duga na traci, Y<ms> (bez broja 5 s, najvise 30 s)
      rainbowMs = *a ? constrain(atol(a), 500L, 30000L) : 5000;
      rainbowT = millis();
      break;
    case 'W':
      startWelcome(true);  // "probaj": pokazi i TID dio, cak i ako je iskljucen
      break;
    case '?':
      Serial.print(F("OK CorsaLight v"));
      Serial.println(FW_VERSION);
      break;
    case 'D': {
      char d[96];
      snprintf(d, sizeof(d), "D rgb=%u,%u,%u bright=%u/%u power=%u mode=%u react=%u tid=%s welcome=%u loops/s=%lu",
               cfg.r, cfg.g, cfg.b, curBright, cfg.bright, cfg.power, cfg.mode, cfg.react,
               tid.ok() ? "OK" : "NE", welcomeLed, loopRate);
      Serial.println(d);
      snprintf(d, sizeof(d), "D led0=%u,%u,%u tid_paritet_greske=%u", leds[0].r, leds[0].g, leds[0].b,
               tid.parityErrors());
      Serial.println(d);
      break;
    }
    case 'T': {
      char d[120];
      tid.probe(d, sizeof(d));
      Serial.println(d);
      tidRetry = 0;  // odmah probaj opet poslati na TID
      break;
    }
  }
}

void readSerial() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') continue;
    if (c == '\n') {
      line[lineLen] = 0;
      if (lineLen) handleLine(line);
      lineLen = 0;
    } else if (lineLen < sizeof(line) - 1) {
      line[lineLen++] = c;
    }
  }
}

// ---------------- LED ----------------
void renderStar(unsigned long now) {
  if (now - starTick >= starStep) {
    starTick = now;
    if (starPos < NUM_LEDS) {
      star[starPos++] = base;
    } else if ((long)(now - starNext) >= 0) {
      starPos = 0;
      starTail = random(10, 60);
      starStep = random(5, 40);
      starNext = now + random(2000, 8000);
    }
    fadeToBlackBy(star, NUM_LEDS, starTail);
  }
  memcpy(leds, star, sizeof(leds));
}

// LED pozdrav (cfg.ledWelcome): 0 s rubova prema sredini, 1 lagano paljenje, 2 klizi s lijeva, 3 iskrice

// Animacije s ekrana "Footwell Lighting Animation" (mod 2-7). Koriste star[] kao spremnik.
//  2 Raindrops       kapi u boji padaju na nasumicna mjesta i gase se
//  3 Random          nasumicne LED-ice u nasumicnim bojama
//  4 Calm            boja lagano "dise"
//  5 Smoke           meko titranje boje, kao dim
//  6 Thunder         tamna boja s naglim bijelim bljeskovima
//  7 Changing Colour cijela traka polako mijenja boju kroz dugu
unsigned long fxTick = 0, fxNext = 0;
uint8_t fxHue = 0, flashLeft = 0;

void renderEffect(unsigned long now) {
  CRGB c = base;  // vec ugasena ako je power 0
  switch (cfg.mode) {
    case 2:  // Raindrops
      if (now - fxTick >= 30) {
        fxTick = now;
        fadeToBlackBy(star, NUM_LEDS, 18);
        if ((long)(now - fxNext) >= 0) {
          uint8_t i = random(NUM_LEDS);
          star[i] = blend(c, CRGB::White, 90);
          fxNext = now + random(80, 450);
        }
      }
      memcpy(leds, star, sizeof(leds));
      break;
    case 3:  // Random
      if (now - fxTick >= 40) {
        fxTick = now;
        fadeToBlackBy(star, NUM_LEDS, 10);
        if ((long)(now - fxNext) >= 0) {
          CRGB r = CHSV(random8(), 255, 255);
          if (!cfg.power) r = CRGB::Black;
          star[random(NUM_LEDS)] = r;
          fxNext = now + random(60, 220);
        }
      }
      memcpy(leds, star, sizeof(leds));
      break;
    case 4: {  // Calm: disanje 5 s
      uint8_t b = beatsin8(12, 40, 255);
      CRGB t = c;
      t.nscale8_video(b);
      fill_solid(leds, NUM_LEDS, t);
      break;
    }
    case 5:  // Smoke: meki sum po traci
      for (uint8_t i = 0; i < NUM_LEDS; i++) {
        uint8_t n = inoise8(i * 40, now / 6);
        n = qadd8(scale8(n, 200), 30);
        CRGB t = c;
        t.nscale8_video(n);
        leds[i] = t;
      }
      break;
    case 6: {  // Thunder
      CRGB dim = c;
      dim.nscale8_video(35);
      if (now - fxTick >= 20) {
        fxTick = now;
        if (flashLeft == 0 && (long)(now - fxNext) >= 0) {
          flashLeft = random(2, 5);  // 2-4 brza bljeska
          fxNext = now + random(1500, 7000);
        }
        fadeToBlackBy(star, NUM_LEDS, 60);
        if (flashLeft && random8() < 90) {
          uint8_t from = random(NUM_LEDS / 2), len = random(6, NUM_LEDS - from);
          for (uint8_t i = from; i < from + len && i < NUM_LEDS; i++) star[i] = CRGB::White;
          flashLeft--;
        }
      }
      for (uint8_t i = 0; i < NUM_LEDS; i++) {
        leds[i] = dim;
        if (cfg.power) leds[i] += star[i];
      }
      break;
    }
    case 7:  // Changing Colour
      if (now - fxTick >= 60) {
        fxTick = now;
        fxHue++;
      }
      fill_solid(leds, NUM_LEDS, cfg.power ? CRGB(CHSV(fxHue, 255, 255)) : CRGB(CRGB::Black));
      break;
  }
}

void renderWelcome(unsigned long now) {
  unsigned long e = now - welcomeT;
  uint8_t p = e >= WIPE_MS ? 255 : (uint32_t)e * 255 / WIPE_MS;  // napredak 0..255
  CRGB c = savedColor();
  CRGB head = blend(c, CRGB::White, 160);
  switch (cfg.ledWelcome) {
    case 1: {  // cijela traka se polako upali
      CRGB t = c;
      t.nscale8_video(p);
      fill_solid(leds, NUM_LEDS, t);
      break;
    }
    case 2: {  // LED po LED s lijeva nadesno, s bijelom glavom
      uint8_t k = (uint16_t)p * NUM_LEDS / 255;
      for (uint8_t i = 0; i < NUM_LEDS; i++)
        leds[i] = i >= k ? CRGB(CRGB::Black) : (i + 1 == k && p < 255 ? head : c);
      break;
    }
    case 3: {  // nasumicne iskrice dok se ne upali sve
      uint8_t want = (uint16_t)p * NUM_LEDS / 255, have = 0;
      for (uint8_t i = 0; i < NUM_LEDS; i++) have += bitRead(sparkleMask, i);
      uint32_t fresh = 0;
      while (have < want) {
        uint8_t i = random(NUM_LEDS);
        if (bitRead(sparkleMask, i)) continue;
        bitSet(sparkleMask, i);
        bitSet(fresh, i);
        have++;
      }
      for (uint8_t i = 0; i < NUM_LEDS; i++)
        leds[i] = !bitRead(sparkleMask, i) ? CRGB(CRGB::Black) : (bitRead(fresh, i) ? CRGB(CRGB::White) : c);
      break;
    }
    default: {  // s oba kraja prema sredini, s bijelom "glavom"
      const uint8_t half = (NUM_LEDS + 1) / 2;
      uint8_t k = (uint16_t)p * half / 255;
      for (uint8_t i = 0; i < NUM_LEDS; i++) {
        bool lit = i < k || i >= NUM_LEDS - k;
        bool edge = (i + 1 == k) || (i == NUM_LEDS - k);
        leds[i] = !lit ? CRGB(CRGB::Black) : (edge && p < 255 ? head : c);
      }
    }
  }
  if (e >= WIPE_MS) {
    welcomeLed = false;
    base = targetColor();
    memcpy(star, leds, sizeof(star));
  }
}

void renderVolume(unsigned long now) {
  unsigned long e = now - volT;
  if (e >= VOL_HOLD + VOL_FADE) {
    volT = 0;
    return;
  }
  uint8_t a = e < VOL_HOLD ? 255 : 255 - (uint32_t)(e - VOL_HOLD) * 255 / VOL_FADE;
  uint8_t lit = ((uint16_t)volCur * NUM_LEDS + volMax / 2) / volMax;
  CRGB on = blend(savedColor(), CRGB::White, 70);
  CRGB off = savedColor();
  off.nscale8(40);
  for (uint8_t i = 0; i < NUM_LEDS; i++) {
    leds[i] = blend(leds[i], i < lit ? on : off, a);
  }
}

void renderPulse(unsigned long now) {
  unsigned long e = now - pulseT;
  if (e >= PULSE_MS) {
    pulse = false;
    return;
  }
  int16_t headPos = (int32_t)e * (NUM_LEDS + 6) / PULSE_MS;
  for (uint8_t j = 0; j < 6; j++) {
    int16_t p = headPos - j;
    if (p >= 0 && p < NUM_LEDS) leds[p] = blend(leds[p], CRGB::White, 255 - j * 40);
  }
}

void renderLeds(unsigned long now) {
  CRGB t = targetColor();
  base.r = approach(base.r, t.r);
  base.g = approach(base.g, t.g);
  base.b = approach(base.b, t.b);
  curBright = approach(curBright, cfg.bright);
  FastLED.setBrightness(curBright);

  if (welcomeLed) renderWelcome(now);
  else if (cfg.mode == 1) renderStar(now);
  else if (cfg.mode >= 2) renderEffect(now);
  else fill_solid(leds, NUM_LEDS, base);

  if (cfg.react && cfg.power && !welcomeLed) {
    if (volT) renderVolume(now);
    if (pulse) renderPulse(now);
  }
  if (rainbowMs) renderRainbow(now);
}

// Easter egg (naredba Y): duga putuje po traci, na pocetku i kraju se lagano utapa
void renderRainbow(unsigned long now) {
  unsigned long e = now - rainbowT;
  if (e >= rainbowMs) {
    rainbowMs = 0;
    return;
  }
  uint8_t a = 255;
  if (e < 400) a = e * 255 / 400;
  else if (rainbowMs - e < 600) a = (rainbowMs - e) * 255 / 600;
  CRGB r[NUM_LEDS];
  fill_rainbow(r, NUM_LEDS, (uint8_t)(e / 8), 255 / NUM_LEDS + 4);
  for (uint8_t i = 0; i < NUM_LEDS; i++) leds[i] = blend(leds[i], r[i], a);
  // i kad je svjetlo priguseno, duga se mora vidjeti
  if (curBright < 70) FastLED.setBrightness(curBright + (uint16_t)(70 - curBright) * a / 255);
}

// ---------------- TID ----------------
void windowOf(const char *s, uint8_t pos, char *out) {
  uint8_t len = strlen(s);
  if (len <= 10) {
    uint8_t pad = (10 - len) / 2;
    for (uint8_t i = 0; i < 10; i++) out[i] = (i >= pad && i < pad + len) ? s[i - pad] : ' ';
  } else {
    uint8_t total = len + 3;  // 3 razmaka izmedu ponavljanja
    for (uint8_t i = 0; i < 10; i++) {
      uint8_t k = (pos + i) % total;
      out[i] = k < len ? s[k] : ' ';
    }
  }
}

// Pozdrav na TID-u. e = ms od trenutka kad je TID budan. Vraca false kad je gotov.
// Stilovi (cfg.welcomeAnim): 0 pojavi se, 1 uklizi, 2 prolazi, 3 slovo po slovo, 4 iz sredine, 5 treptanje
bool welcomeFrame(unsigned long e, char *out) {
  char a[10], b[10];
  windowOf(cfg.welcome1, 0, a);  // pozdravi imaju najvise 10 znakova, pa su centrirani
  windowOf(cfg.welcome2, 0, b);
  bool two = cfg.welcome2[0];

  if (cfg.welcomeAnim == 2) {
    // traka: 10 razmaka + pozdrav + 2 razmaka + ime, prozor od 10 znakova putuje po njoj
    const uint16_t IN = 800, HOLD1 = 1500, PASS = 960, HOLD2 = 2000;
    unsigned long total = IN + HOLD1 + PASS + (two ? HOLD2 : 0);
    if (e >= total) return false;
    uint8_t off;
    if (e < IN) off = e / 80;
    else if (e < IN + HOLD1) off = 10;
    else if (e < IN + HOLD1 + PASS) off = 10 + (e - IN - HOLD1) / 80;
    else off = 22;
    for (uint8_t i = 0; i < 10; i++) {
      uint8_t p = off + i;
      out[i] = (p >= 10 && p < 20) ? a[p - 10] : (p >= 22 && p < 32) ? b[p - 22] : ' ';
    }
    return true;
  }

  const uint16_t D1 = 3000, D2 = 2000;
  if (e >= D1 + (two ? D2 : 0)) return false;
  bool first = e < D1;
  unsigned long pe = first ? e : e - D1;
  memcpy(out, first ? a : b, 10);
  switch (cfg.welcomeAnim) {
    case 1: {  // uklizi s desne strane
      uint8_t shift = pe >= 800 ? 0 : 10 - pe / 80;
      for (int8_t i = 9; i >= 0; i--) out[i] = i - shift >= 0 ? out[i - shift] : ' ';
      break;
    }
    case 3: {  // slovo po slovo
      uint8_t n = pe / 110;
      for (uint8_t i = 0; i < 10; i++)
        if (i >= n) out[i] = ' ';
      break;
    }
    case 4: {  // iz sredine prema rubovima
      uint8_t k = pe / 120;
      for (uint8_t i = 0; i < 10; i++) {
        uint8_t d = i < 5 ? 4 - i : i - 5;
        if (d >= k) out[i] = ' ';
      }
      break;
    }
    case 5:  // treptanje (samo pozdrav), pa ostane
      if (first && pe < 1500 && (pe / 250) % 2 == 1) memset(out, ' ', 10);
      break;
  }
  return true;
}

void updateTid(unsigned long now) {
  if ((long)(tidPauseUntil - now) > 0) return;
  if (now - tidLast < 50) return;
  tidLast = now;

  const char *src = "";
  char tmp[11];
  char out[10];
  uint8_t id = 0, bars = 0;
  bool wel = false;
  if (welcomeTid) {
    if (!welcomeTidStarted) {
      memset(out, ' ', 10);  // dok TID ne potvrdi prvu poruku saljemo prazno, tek onda krece pozdrav
      wel = true;
    } else {
      wel = welcomeFrame(now - welcomeTidT, out);
    }
    if (!wel) welcomeTid = false;
  }
  if (!wel) {
    if (volT && volTid && now - volT < VOL_HOLD + VOL_FADE) {
      id = 2;
      snprintf(tmp, sizeof(tmp), "VOL %u", volCur);
      src = tmp;
      bars = ((uint16_t)volCur * 10 + volMax / 2) / volMax;
      if (bars == 0 && volCur) bars = 1;
    } else if (msgT && now - msgT < 2000) {
      id = 3;
      src = msgText;
    } else if (nowText[0]) {
      id = 4;
      src = nowText;
    } else {
      id = 5;
      src = cfg.idle;
    }
  }
  // volume overlay se gasi i kad je R0, pa ga tu pocistimo
  if (volT && !(cfg.react && cfg.power) && now - volT >= VOL_HOLD + VOL_FADE) volT = 0;

  if (wel) {
    srcId = 0;  // poslije pozdrava tekst krece ispocetka
  } else {
    uint8_t len = strlen(src);
    if (id != srcId || (id == 4 && nowVersion != shownVersion)) {
      srcId = id;
      shownVersion = nowVersion;
      scrollPos = 0;
      scrollNext = now + 1500;  // tekst prvo malo stoji, pa se krene vrtit
    } else if (len > 10 && (long)(now - scrollNext) >= 0) {
      scrollPos = (scrollPos + 1) % (len + 3);
      scrollNext = now + 350;
    }
    windowOf(src, scrollPos, out);
  }
  if (!tid.ok() && now - tidRetry < 2000) return;  // TID ne odgovara: probaj opet za 2 s
  // stari kod je TID osvjezavao svake sekunde, pa i mi (i kad se tekst ne mijenja)
  if (tid.ok() && memcmp(out, shown, 10) == 0 && bars == shownBars && now - tidSent < 1000) return;
  tid.setBargraph(bars);
  if (tid.show(out)) {
    memcpy(shown, out, 10);
    shownBars = bars;
    tidSent = now;
    if (welcomeTid && !welcomeTidStarted) {
      welcomeTidStarted = true;  // TID je sad budan: od ovog trenutka broji pozdrav
      welcomeTidT = now;
    }
  }
  tidRetry = now;
}

// ---------------- main ----------------
void setup() {
  Serial.begin(BAUD);
  loadSettings();
  FastLED.addLeds<WS2812B, LED_PIN, GRB>(leds, NUM_LEDS).setCorrection(TypicalLEDStrip);
  // Traka se napaja preko Una iz USB-a headunita (~0.5 A). Vise od toga = pad napona
  // i nasumicne boje. Ako trake dobije svoje 5 V napajanje, ovo se moze dignuti.
  FastLED.setMaxPowerInVoltsAndMilliamps(5, LED_MAX_MA);
  FastLED.clear(true);
  tid.begin();
  randomSeed(analogRead(A0));
  startWelcome();
  Serial.print(F("HELLO CorsaLight v"));
  Serial.println(FW_VERSION);
}

void loop() {
  readSerial();
  unsigned long now = millis();
  if (now - lastFrame >= 10) {
    lastFrame = now;
    renderLeds(now);
    FastLED.show();
  }
  updateTid(now);
  saveIfDue(now);
  loopCount++;
  if (now - loopT >= 1000) {
    loopRate = loopCount;
    loopCount = 0;
    loopT = now;
  }
}
