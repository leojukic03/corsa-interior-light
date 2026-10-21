// Provjera mogu li D5, D6, D7 stvarno spustiti/dici liniju (TID je spojen i drzi pull-up).
// Ako pin kao OUTPUT LOW cita 1, pin ne moze vuci dolje -> ostecen ili kratki spoj.
const byte pins[] = {5, 6, 7, 8};

void setup() {
  Serial.begin(9600);
  delay(500);
  for (byte i = 0; i < sizeof(pins); i++) {
    byte p = pins[i];
    pinMode(p, OUTPUT);
    digitalWrite(p, LOW);
    delayMicroseconds(200);
    byte lo = digitalRead(p);
    digitalWrite(p, HIGH);
    delayMicroseconds(200);
    byte hi = digitalRead(p);
    pinMode(p, INPUT);
    delayMicroseconds(500);
    byte in = digitalRead(p);
    Serial.print(F("PIN D"));
    Serial.print(p);
    Serial.print(F(": OUT LOW cita "));
    Serial.print(lo);
    Serial.print(F(", OUT HIGH cita "));
    Serial.print(hi);
    Serial.print(F(", pusten cita "));
    Serial.print(in);
    Serial.println(lo == 0 && hi == 1 ? F("  -> OK") : F("  -> PROBLEM"));
  }
}

void loop() {}
