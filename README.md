# Spawner Market – Spawnerhandel in Minecraft (Fabric 1.21.11)

Spawner kaufen und verkaufen direkt im Spiel – mit derselben Liquid-Glass-Oberfläche wie der
Liquid Player. **Reine Client-Mod:** Der Server braucht nichts, alles läuft über ein öffentliches
Relay (MQTT, `broker.emqx.io`) zwischen allen Spielern mit der Mod.

## Installation

1. Fabric Loader und **Fabric API** für 1.21.11 installieren.
2. `build/libs/spawner-market-1.4.3.jar` in den `mods`-Ordner kopieren.
3. Minecraft starten, einem Server beitreten, **H** drücken.

## Willkommen & Clan (hugoSMP)

Jedes Mal, wenn du hugoSMP joinst, kommt beim ersten **H** ein Willkommensfenster: „Hallo [Name], mach
/clan info, um fortzufahren“. Der Knopf **/clan info ausführen** schickt den Befehl (oder du
tippst ihn selbst in den Chat). Die Mod liest aus der Antwort dein **Clan-Kürzel** mit seinen
Farben, z. B. **[FWM5]**, und zeigt es ab dann überall in der Mod **vor deinem Namen** – auch bei
den anderen Spielern mit der Mod. **Weiter** wird erst frei, wenn der Server auf /clan info geantwortet hat – auch wenn du in keinem Clan bist (dann bleibt nur dein Name, ohne Kürzel). **Discord**
öffnet den Spawner-Market-Discord. Danach geht es direkt ins Menü.

Clan gewechselt? **Zahnrad → Clan → Aktualisieren** liest „/clan info“ neu.

## Aufbau

| Links: **Trade-Chats** | Mitte: **Spawnerhandel** | Rechts: **Vouches** |
|---|---|---|
| ein Chat pro Trade | Verkauf · Ankauf · Deals · Preise | Bewertungen des gewählten Spielers |

### Mitte – Spawnerhandel
- **Verkauf** und **Ankauf** sind getrennte Listen der Angebote auf deinem Server: Unter Verkauf
  stehen die Spieler, die Spawner verkaufen (günstigster zuerst), unter Ankauf die, die welche
  suchen (höchstes Gebot zuerst). Rechts in jeder Zeile der Gesamtpreis. Unten links
  **Meine Gebote** (nur deine eigenen Angebote, auf jedem Server), rechts daneben **+ Angebot**.
  Grüner Punkt = Anbieter ist online.
- **Spawner-Filter:** unter den Tabs eine Reihe mit den Spawner-Modellen – Klick zeigt nur diesen
  Spawner (in Markt, Deals und Preise), nochmal klicken = alle.
  - Fremdes Angebot (Maus drüber): **Handeln** öffnet einen Trade-Chat, **TPA** schickt `/tpa <Name>`.
  - Eigenes Angebot: **✕** löscht das Angebot.
- **+ Angebot erstellen:** Verkaufen/Kaufen, Spawner (Kuh, Golem, Spinne, Piglin, Skelett,
  Creeper, Lohe), Anzahl, Preis pro Stück
  (`250k`, `1,5m`, `2.000.000`), Notiz. Zeigt den aktuellen Marktpreis zur Orientierung.
- **Deals:** abgeschlossene Handel der letzten 30 Tage (Verkäufer → Käufer, Preis). Deals kann
  man nicht eintippen – sie entstehen nur durch einen Vouch im Trade (siehe unten).
- **Preise:** pro Spawner der höchste Ankaufs- und der höchste Verkaufspreis, dazu der Ø-Preis
  aus den Deals (nach Stückzahl gewichtet). Klick zeigt die passenden Angebote.

### Links – Trade-Chats
- Jeder Trade hat seinen eigenen Chat: **Handeln** an einem Angebot öffnet ihn, beim Anbieter
  taucht er automatisch auf. Oben steht, um welches Angebot es geht.
- Bei **Handeln** wählst du zuerst, **wie viele** du kaufen (bzw. verkaufen) willst – „Alle“, mit
  − / + oder eingetippt. Nach dem Deal wird das Angebot um diese Menge kleiner: Kaufst du 2 von 10,
  stehen danach noch 8 zum Verkauf. Kauft jemand alle, verschwindet das Angebot.
- Die erste Nachricht steht oben, neue kommen darunter.
- **Ein Trade ist erst abgeschlossen, wenn beide füreinander gevoucht haben.** Wer zuerst vouched,
  sieht „wartet auf …“, der andere „du bist dran“ (oben im Chat, plus Hinweis im Verlauf).
- Haben **beide positiv** (**+ Deal**) gevoucht, wird der Deal eingetragen und das Angebot
  verschwindet. Ist mindestens ein Vouch **neutral** oder **negativ**, gibt es keinen Deal und das
  Angebot bleibt. In jedem Fall wird der Chat danach bei beiden gelöscht – ein neuer Deal bekommt
  einen neuen Chat.
- **✕** bricht den Trade ohne Vouch und ohne Deal ab.
- **Namen laufen automatisch mit:** Die Namen im Deal kommen aus den Minecraft-Konten der beiden
  Trade-Partner, nicht aus einem Textfeld, und beide Clients tragen den Deal unter ihrer eigenen
  Identität ein.
- Beide Spieler brauchen mindestens Version 1.3.2 – ältere Versionen schließen den Trade schon
  nach einem Vouch.
- TPA-Anfragen von anderen Mod-Spielern erscheinen oben mit **Ja** (schickt `/tpaccept <Name>`).

### Rechts – Vouches
- Klick auf ein Angebot, einen Deal oder einen Namen zeigt die Vouches dieses Spielers
  (+ / −, Text, Datum) und ob er gerade online ist und wo.
- **Vouchen geht nur nach einem Trade** – also nur für den Partner eines offenen Trade-Chats,
  und erst wenn im Chat **mindestens 2 Nachrichten** stehen (je eine von beiden Seiten). Dann Text
  eingeben und **+ Deal** oder **− Neg.** klicken – **○ Neutral** braucht keinen Text (der Trade kam
  ja nicht zustande).
- Hat der Partner schon gevoucht, kannst du immer zurückvouchen, auch wenn bei dir Nachrichten fehlen.
- **Pro Trade vouched jeder genau einmal** für den anderen – egal ob Deal, neutral oder negativ.
  Ein Vouch eines offenen Trades lässt sich nicht löschen.
- Vouches stacken: Jeder Trade bringt neue Vouches, auch vom selben Spieler. Ein positiver Vouch
  zählt erst, wenn der Deal eingetragen ist (vorher „unbestätigt“).
- Ohne Auswahl: Rangliste der Top-Händler. Dein Kopf oben rechts zeigt deine eigenen Vouches.

### Zahnrad
Farbthema, Black Mode, Benachrichtigungen an/aus, Deckkraft und der **Preiswecker**: pro
Spawner einen Preis eintragen, dazu **Kaufen bis** (Meldung, wenn jemand zu diesem Preis oder
billiger verkauft) oder **Verkaufen ab** (wenn jemand so viel oder mehr zahlt). Gilt für Angebote
auf deinem aktuellen Server und kommt als Banner, unabhängig von den Angebots-Filtern.

### Benachrichtigungen
Kleine Banner oben im Bild (mit Ton), wenn jemand ein **neues Angebot** aufmacht: Spawner, Anzahl,
Preis pro Stück, Verkauf/Ankauf, von wem und wie der Preis zum Schnitt der Deals steht. Dazu
**jeder Kauf/Verkauf** (fertiger Deal: Verkäufer → Käufer), neue Trades an deinen Angeboten,
Trade-Nachrichten, bestätigte/beendete Trades und TPA-Anfragen. Die Banner liegen über allem,
auch über Inventar, Kisten und dem Spawnerhandel-Menü.
Unter **Zahnrad → Filter & Anzeige** ist alles einstellbar:
- **Angebote:** Verkauf/Ankauf, nur dein Server, nur gute Preise, nur bestimmte Spawner.
- **Trades:** neue Trades, Nachrichten, Deal/Trade beendet, TPA, jeder Kauf/Verkauf auf dem Markt.
- **Anzeige:** Position (links/Mitte/rechts), Dauer, Ton, über anderen Menüs, über dem
  Spawnerhandel-Menü.
- **Wecker:** erklärt den Preiswecker, zeigt welche gestellt sind und führt zum Einstellen.
- Unter jeder Einstellung steht kurz, was sie macht. **Test** zeigt Beispiel-Banner passend zum
  offenen Tab – im Tab „Wecker“ (und oben auf der Preiswecker-Seite) einen Preiswecker-Banner.

## Einstellungen

`config/spawner-market.json` (nur Einstellungen – das darf in ein Modpack):
- `tpaCommand` / `tpaAcceptCommand`: falls dein Server andere Befehle hat (Standard `tpa` / `tpaccept`).

**Pro Minecraft-Account** in deinem Benutzerordner, nicht im Spiel- oder Modpack-Ordner:
`C:\Users\<du>\.spawner-market\accounts\<UUID>.json` – Clan-Kürzel, offene Trade-Chats,
Preiswecker und was du veröffentlicht hast (wird nach jedem Verbinden neu gesendet). So nimmt ein
weitergegebenes Modpack nie deine Trades oder deinen Clan mit, und mehrere Accounts auf einem PC
haben jeweils ihre eigenen Daten. Ältere Daten aus der Config werden einmalig dorthin verschoben.

## Wichtig zu wissen

- Das Relay ist öffentlich und ohne Login. Die Mod prüft, dass jeder nur unter seiner eigenen UUID
  schreibt, aber das Relay selbst kennt keine Konten: **Wer das Protokoll gezielt nachbaut, kann
  Angebote, Vouches und (mit mehr Aufwand) auch Deals fälschen.** Mit der normalen Mod geht das
  nicht. Wirklich fälschungssicher wäre es nur mit einem eigenen Server mit Login.
  Vouches und Preise sind ein Anhaltspunkt, kein Beweis – handle trotzdem vorsichtig.
- Angebote verschwinden nach 7 Tagen, Deals zählen 30 Tage.
- Trade-Chats stehen in der Reihenfolge, in der die Nachrichten ankommen – die Uhren der Spieler
  gehen nie genau gleich, nach Absenderzeit sortiert landeten Antworten sonst über der Frage.
- Das Relay hebt pro Spieler nur die letzte Nachricht auf. Darum reisen mit jeder Nachricht deine
  letzten 5 mit: Was dein Partner verpasst hat (offline, Verbindung kurz weg), wird mit deiner
  nächsten Nachricht an der richtigen Stelle nachgetragen.
- Preise sind in der Währung des jeweiligen Servers – darum zeigt der Markt deinen Server
  (bist du auf keinem, alle Server).

## Discord

Mit dem Programm in [`discord-bridge/`](discord-bridge/README.md) erscheinen Verkauf, Ankauf,
Preise und Vouches live in vier Discord-Kanälen (über Webhooks).

## Bauen

```
gradlew build
```

Selbsttest im echten Spiel (ohne Relay, Ergebnis und Screenshots in `run-prod/`; die Welt muss
unter `run-prod/saves` liegen):

```
gradlew prodClient -Pselftest -Pworld="New World"
```
