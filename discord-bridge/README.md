# Spawner Search → Discord

Ein kleines Programm, das den Spawner-Search aus der Mod **live** in deinen Discord bringt:

| Kanal | Was dort steht |
|---|---|
| **#verkauf** | Eine Live-Nachricht mit allen Verkaufsangeboten, pro Spawner sortiert, günstigster zuerst |
| **#ankauf** | Eine Live-Nachricht mit allen Ankaufsgeboten, höchstes Gebot zuerst |
| **#preise** | Pro Spawner höchster Verkaufs- und Ankaufspreis, Ø aus den Deals, dazu die letzten Deals |
| **#vouches** | Oben eine Live-Übersicht (Top-Händler, wie Vouches zählen), darunter jeder Vouch als eigene Nachricht (positiv erst, wenn der Deal von beiden bestätigt ist) |

Die Nachrichten in #verkauf, #ankauf und #preise werden **bearbeitet**, sobald sich im Markt etwas
ändert – es kommen also keine hundert neuen Nachrichten, sondern eine, die immer aktuell ist.

## Warum ein eigenes Programm?

Discord-Webhooks können nur empfangen. Irgendetwas muss die Daten aus dem Markt lesen und
schicken. Würde das jede Mod selbst machen, stünden die Webhook-Links in jeder Mod – jeder könnte
damit deinen Kanal zuspammen. Deshalb läuft das hier **einmal** bei dir, und nur du kennst die Links.

Es ist nur „live“, solange es läuft: auf deinem PC (solange er an ist) oder dauerhaft auf einem
Server / VPS / Raspberry Pi. Es braucht **Java 21 oder neuer** (das, was du für Minecraft
installiert hast, reicht meist).

## Einrichten

1. **Webhooks anlegen** – für jeden der vier Kanäle in Discord:
   Kanal → ⚙️ *Kanal bearbeiten* → *Integrationen* → *Webhooks* → *Neuer Webhook* →
   *Webhook-URL kopieren*.
2. Die Zip in einen eigenen Ordner entpacken (`spawner-search-discord.jar`, `start.bat`, `stop.bat` /
   `start.sh`, `einrichten.bat`).
3. `einrichten.bat` doppelklicken: Es fragt nacheinander nach den vier URLs (einfügen mit
   Rechtsklick) und nach deinem Server und speichert alles in `bridge.json`. Oder von Hand:
   einmal `start.bat` starten (legt `bridge.json` an) und die Datei mit einem Editor füllen:
4. So sieht `bridge.json` dann aus:

   ```json
   {
     "server": "donutsmp.net",
     "webhooks": {
       "verkauf": "https://discord.com/api/webhooks/…",
       "ankauf": "https://discord.com/api/webhooks/…",
       "preise": "https://discord.com/api/webhooks/…",
       "vouches": "https://discord.com/api/webhooks/…"
     },
     "maxPerSpawner": 10,
     "refreshMinutes": 3,
     "postExistingVouches": false
   }
   ```

   - `server`: nur Angebote und Deals von diesem Minecraft-Server zeigen (Adresse wie in
     Minecraft, ohne `:25565`). Leer lassen = alle Server, dann steht der Server bei jedem Angebot.
   - `maxPerSpawner`: wie viele Angebote pro Spawner höchstens in der Nachricht stehen (1–25).
   - `refreshMinutes`: alle wie viele Minuten die Live-Nachrichten neu gesendet werden, auch
     wenn sich nichts geändert hat (Standard 3). Änderungen erscheinen sofort.
   - `postExistingVouches`: `true` postet beim ersten Start auch alle alten Vouches. Standard:
     nur neue.
5. `start.bat` starten. Die Bridge läuft dann **im Hintergrund ohne Fenster** (mit `javaw`).
   Nach ein paar Sekunden erscheinen die Nachrichten in den Kanälen.
   - **Beenden:** `stop.bat` (sie hört nach ein paar Sekunden auf).
   - **Was sie tut** (aktualisiert, aufgefrischt, Fehler von Discord): steht in `bridge.log`.
   - Startest du sie zweimal, beendet sich der zweite Start von selbst.
   - **Zum Zuschauen** im Fenster: `java -jar spawner-search-discord.jar` in einer
     Eingabeaufforderung im Ordner.
6. **Autostart** (startet bei jeder Windows-Anmeldung mit): `Win+R` → `shell:startup` → Enter.
   In den Ordner, der aufgeht, mit **Rechtsklick → Neu → Verknüpfung** eine Verknüpfung anlegen
   und als Speicherort eintragen:

   ```
   javaw -jar "C:\Pfad\zu\spawner-search-discord.jar"
   ```

   Name z. B. „Spawner-Search Discord“ → Fertig stellen. Ein Virenscanner kann fragen, ob er das
   erlauben soll – das ist dieser Start der Bridge.

**Probelauf ohne Discord:** `java -jar spawner-search-discord.jar --dry-run` zeigt im Fenster,
was gepostet würde, und beendet sich.

## Gut zu wissen

- **Die Webhook-Links sind wie Passwörter.** Wer sie hat, kann in deinen Kanälen posten. Teile
  `bridge.json` mit niemandem und lade sie nirgends hoch.
- Am besten stellst du die vier Kanäle so ein, dass **nur Webhooks** schreiben dürfen.
- Löscht jemand eine Live-Nachricht, legt das Programm beim nächsten Update eine neue an.
- `bridge-state.json` merkt sich, welche Nachrichten bearbeitet werden und welche Vouches schon
  gepostet sind. Löschst du sie, werden die Live-Nachrichten neu angelegt (die alten bleiben
  dann stehen – einfach von Hand löschen).
- Das Programm liest nur mit, es schreibt nichts in den Markt.
- Spielernamen und Texte kommen von den Spielern. Sie werden so gesendet, dass niemand gepingt
  wird (auch kein `@everyone`).

## Bauen

```
../gradlew -p . build
```

Die fertige Datei liegt dann in `build/libs/spawner-search-discord.jar`.
