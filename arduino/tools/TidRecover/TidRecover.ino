// Pokusaj "odglaviti" TID bez vadjenja konektora. Nakon svakog pokusaja provjeri
// pusta li TID linije (SDA/SCL bez pull-upa = 1) i javlja li se na MRQ.
const byte SDA_ = 7, SCL_ = 5, MRQ_ = 6;

void odLow(byte p) { digitalWrite(p, LOW); pinMode(p, OUTPUT); }
void odRel(byte p) { pinMode(p, INPUT); }

void state(const __FlashStringHelper *name) {
  odRel(SDA_); odRel(SCL_);
  delayMicroseconds(300);
  byte a = digitalRead(SDA_), b = digitalRead(SCL_);
  // handshake: MRQ low -> TID spusti SDA?
  digitalWrite(MRQ_, LOW);
  unsigned long t = millis();
  while (digitalRead(SDA_) == HIGH && millis() - t < 200) {}
  bool ack = digitalRead(SDA_) == LOW;
  digitalWrite(MRQ_, HIGH);
  delay(5);
  Serial.print(name);
  Serial.print(F(": SDA="));
  Serial.print(a);
  Serial.print(F(" SCL="));
  Serial.print(b);
  Serial.print(F(" odgovor_na_MRQ="));
  Serial.println(ack ? F("DA") : F("NE"));
}

void setup() {
  Serial.begin(9600);
  pinMode(MRQ_, OUTPUT);
  digitalWrite(MRQ_, HIGH);
  delay(300);
  state(F("pocetak"));

  // 1) MRQ visoko 100 ms (kraj prijenosa)
  digitalWrite(MRQ_, HIGH);
  delay(100);
  state(F("1 MRQ high"));

  // 2) do 40 taktova na SCL dok TID ne pusti SDA, pa stop
  for (byte i = 0; i < 40; i++) {
    odLow(SCL_); delayMicroseconds(60);
    odRel(SCL_);
    unsigned long t = micros();
    while (digitalRead(SCL_) == LOW && micros() - t < 2000) {}
    delayMicroseconds(60);
  }
  odLow(SDA_); delayMicroseconds(100); odRel(SCL_); delayMicroseconds(100); odRel(SDA_);
  delay(20);
  state(F("2 SCL taktovi"));

  // 3) MRQ nisko 50 ms pa visoko
  digitalWrite(MRQ_, LOW);
  delay(50);
  digitalWrite(MRQ_, HIGH);
  delay(50);
  state(F("3 MRQ puls"));

  // 4) svi vodovi nisko 200 ms, pa pusteni
  odLow(SDA_); odLow(SCL_); digitalWrite(MRQ_, LOW);
  delay(200);
  digitalWrite(MRQ_, HIGH); odRel(SCL_); odRel(SDA_);
  delay(50);
  state(F("4 sve nisko"));
}

void loop() {}
