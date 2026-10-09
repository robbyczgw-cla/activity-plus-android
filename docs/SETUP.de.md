# Activity+ für Android einrichten

[English](SETUP.md)

Alles außer dem Profi-Modus funktioniert direkt nach dem ersten Start. Der Profi-Modus ist optional.

## 1. Installieren

1. Die APK von [activityplus.xyz](https://activityplus.xyz/#android) oder aus den
   [Releases](https://github.com/robbyczgw-cla/activity-plus-android/releases) laden und öffnen.
2. Android fragt, ob der Browser oder Dateimanager Apps installieren darf. Erlauben, installieren, danach
   die Erlaubnis wieder ausschalten.

Updates installieren sich über die alte Version, solange sie von hier kommen: Alle Releases sind mit
demselben Schlüssel signiert (Zertifikat SHA-256 `07:38:FA:A1:25:50:6A:DD:65:72:8D:08:7D:DB:AE:79:CB:A8:C2:AD:87:B3:96:C0:CC:8D:15:03:32:51:C3:C1`).

## 2. Erster Start

1. **Sprache**: die erste Zeile auf dem Begrüßungsbildschirm (ab Android 13). Standard ist die Handysprache.
2. **Nutzungszugriff**: *Nutzungszugriff öffnen* antippen und Activity+ einschalten. Ohne ihn sieht
   Activity+ nicht, welche App auf dem Bildschirm ist und wie viele Daten und wie viel Speicher jede App nutzt.
3. **Benachrichtigungen**: für den Live-Wert in der Statusleiste und für Warnungen.
4. *Los geht's* antippen.

## 3. Damit im Hintergrund weiter gemessen wird

Manche Hersteller beenden Apps im Hintergrund von sich aus. Dann verschwindet der Wert in der Statusleiste,
der Verlauf bekommt Lücken und Widgets bleiben stehen. Activity+ einmal erlauben (Menünamen je nach Version):

| Hersteller | Einstellung |
|---|---|
| vivo (OriginOS, Funtouch) | Akku → Hintergrund-Stromverbrauch → Activity+ → erlauben; Autostart an; in den letzten Apps sperren |
| Xiaomi (HyperOS, MIUI) | App-Info → Akkusparmodus → Keine Einschränkungen; Autostart an |
| Samsung (One UI) | App-Info → Akku → Nicht eingeschränkt; aus „Apps im Standby“ entfernen |
| OnePlus / Oppo / Realme | App-Info → Akkunutzung → Hintergrundaktivität und automatisches Starten erlauben |
| Pixel und andere | App-Info → Akku → Uneingeschränkt |

Erscheint der Wert nicht in der Statusleiste: prüfen, ob das Handy dort Benachrichtigungssymbole anzeigt
(manche Hersteller zeigen standardmäßig nur eine Zahl).

## 4. Profi-Modus (optional)

Android lässt Apps den Hintergrundverbrauch, den Arbeitsspeicher und die CPU anderer Apps nicht sehen. Der
Profi-Modus liest die Berichte von Android einmal, wenn du *Jetzt messen* tippst (Apps oder Akku → *Genau
messen*). Er führt nur drei lesende Berichte aus (`dumpsys batterystats`, `meminfo`, `cpuinfo`) und lehnt alles
andere ab. Activity+ hat keine Internet-Berechtigung, nichts davon kann das Handy verlassen.

Es gibt zwei Wege. Beide lassen sich kombinieren.

| | Freigabe per PC | Shizuku |
|---|---|---|
| Akku pro App seit der letzten Ladung, mit Hintergrund | ja | ja |
| Arbeitsspeicher und CPU pro App | nein (Android hält diese Berichte für Apps verschlossen) | ja |
| Entwickleroptionen danach | dauerhaft aus | beim Messen nötig |
| Nach einem Neustart | bleibt | Shizuku neu starten |

### Freigabe per PC (Akku pro App, dauerhaft)

1. Einstellungen → Über das Telefon → 7× auf die Build-Nummer tippen (vivo: Softwareversion), damit die
   Entwickleroptionen erscheinen. **USB-Debugging** einschalten.
2. Handy mit einem PC mit [adb](https://developer.android.com/tools/releases/platform-tools) verbinden und die
   Abfrage auf dem Handy bestätigen.
3. Ausführen (Activity+ zeigt diese Zeile mit Kopierknopf):
   ```
   adb shell "pm grant xyz.activityplus.android android.permission.DUMP && pm grant xyz.activityplus.android android.permission.PACKAGE_USAGE_STATS && pm grant xyz.activityplus.android android.permission.INTERACT_ACROSS_USERS"
   ```
4. **Etwa zehn Sekunden warten**: Android speichert die Freigabe kurz danach, ein Neustart in dieser Zeit verwirft sie.
5. USB-Debugging und Entwickleroptionen wieder ausschalten.
6. In Activity+ steht unter *Genau messen* jetzt **Bereit (PC-Freigabe, nur Akku)**.

Ohne Kabel? *Debugging über WLAN* (ab Android 11) geht genauso: einmal mit `adb pair <ip>:<port> <code>` koppeln,
dann `adb connect <ip>:<port>` und die Zeile oben ausführen.

Manche Hersteller blockieren `pm grant`, bis ein Zusatzschalter an ist, nur während der Einrichtung:
Xiaomi: *USB-Debugging (Sicherheitseinstellungen)*; OnePlus/Oppo: *Berechtigungsüberwachung deaktivieren*
(neuer: *Systemoptimierung deaktivieren*).

### Shizuku (Akku, Arbeitsspeicher und CPU pro App)

1. [Shizuku](https://shizuku.rikka.app/) installieren und wie in der App beschrieben über *Debugging über WLAN* starten.
2. In Activity+ *Genau messen* → *In Shizuku erlauben* → erlauben.
3. *Jetzt messen* tippen. Danach kannst du die Entwickleroptionen ausschalten; das Ergebnis bleibt in Activity+.

Shizuku stoppt beim Neustart des Handys. Nur neu starten, wenn du messen willst.

## 5. Banking-Apps und Sicherheit

- Banking- und Ausweis-Apps verweigern oft den Dienst, solange die Entwickleroptionen an sind. Mit der
  PC-Freigabe schaltest du sie direkt nach Schritt 5 aus und behältst die Profi-Akkuwerte. Mit Shizuku nur zum
  Messen einschalten.
- Activity+ nutzt keine Bedienungshilfen, kein Overlay und kein Root, die üblichen Auslöser solcher Prüfungen.
- Nach dem Koppeln eines Computers für Debugging über WLAN: ihn unter *Debugging über WLAN → Gekoppelte Geräte*
  entfernen und in den Entwickleroptionen *USB-Debugging-Autorisierungen aufheben* antippen.
- Die Freigabe erlaubt Activity+, Systemberichte zu lesen, die grundsätzlich mehr als Akkuwerte enthalten.
  Activity+ liest nur den Akkubericht, hat keine Internet-Berechtigung, und der Quellcode liegt in diesem Repo.
  Mit der Deinstallation von Activity+ ist die Freigabe weg.

## 6. Wenn die Freigabe fehlt

Sie übersteht Neustarts und das Ausschalten der Entwickleroptionen. Weg ist sie nach Deinstallation oder
Neuinstallation von Activity+, nach einem Zurücksetzen und unter Android 11–14 manchmal nach einem Update von
Activity+. *Genau messen* zeigt dann *Nicht eingerichtet*; einfach Abschnitt 4 wiederholen. Alles andere
funktioniert auch ohne sie.
