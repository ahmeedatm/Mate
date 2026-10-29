# Sécurité du lien tablette ↔ ordinateur

Le jeton donné à l'appairage est la seule protection du pilotage de l'ordinateur (touches perso, macros, applis, presse-papiers). Il ne doit jamais pouvoir être lu ni obtenu par quelqu'un d'autre sur le même WiFi. Correctif de la carte COMP-48 (audit de vendabilité du 29/10/2026).

## Ce qui était possible avant

| Chemin | Comment | Difficulté |
|---|---|---|
| Fausse réponse à la recherche WiFi | L'identifiant d'un ordinateur est public (`GET /health`). Un attaquant répond à la recherche UDP avec cet identifiant ; la tablette remplace l'adresse de l'ordinateur par la sienne (`Connection.kt`) et lui envoie le jeton à la requête suivante. | Facile, sans écoute du réseau |
| HTTP en clair | Le jeton est dans chaque requête (`Authorization: Bearer`) et dans la réponse de l'appairage. | Réseau ouvert, mot de passe WiFi connu, ou détournement ARP |
| Pavé tactile | Le jeton était écrit dans chaque paquet UDP. | Comme le HTTP |

## Ce qui protège maintenant

- **HTTPS en WiFi, HTTP en local seulement.** Le compagnon crée au premier lancement un certificat auto-signé (`backend/src/tls.ts`, `.data/tls.json`, droits 600, valable jusqu'en 2049). Le WiFi passe par le port HTTPS (`TLS_PORT`, par défaut `PORT + 1` : 3001), annoncé dans la réponse à la recherche (`tlsPort`). Le port HTTP (3000) n'écoute plus que sur 127.0.0.1 : page locale, extension du navigateur, hooks de Claude, MCP, tablette en USB (`adb reverse`). Avec `HOST=127.0.0.1`, rien ne change : HTTP local seulement.
- **Certificat épinglé.** La tablette retient l'empreinte SHA-256 du certificat à l'appairage (`Computer.pin`) et refuse tout autre certificat (`net/Pinning.kt`, `PinnedTrust`). Une fausse réponse à la recherche mène vers un certificat inconnu : la connexion échoue avant tout envoi du jeton. Un ordinateur épinglé ne prend jamais une adresse en clair venue de la recherche.
- **Jamais de jeton en clair hors de la tablette.** `Http.kt` refuse d'envoyer un jeton en HTTP à une autre adresse que 127.0.0.1 / localhost (`InsecureComputerException`). Un ordinateur appairé avant ce changement s'affiche « jeton refusé, toucher pour réappairer » : il faut le réappairer une fois.
- **Pavé tactile signé.** Le paquet porte un HMAC-SHA256 de son contenu par le jeton (`pointerSignature`, `PointerPackets.signature`), plus le jeton lui-même. Le compagnon écoute l'UDP sur les deux ports (la tablette l'envoie au port de son adresse).

## Appairage : le code lie le certificat

Épingler le certificat ne suffit pas si un intercepteur s'interpose pendant l'appairage lui-même : la tablette épinglerait le sien. Le code à 6 chiffres sert donc aussi à vérifier le certificat. Il est **affiché par la tablette et saisi sur l'ordinateur** (sens inverse d'avant) : seul ce sens se protège sans cryptographie maison. Un code saisi sur la tablette puis renvoyé se devine hors ligne en moins d'une seconde par qui voit la preuve.

```
tablette                                   ordinateur
  nombre secret t, engagement H(t)
  ── POST /pair/request {commit: H(t)} ──▶  nombre o (après l'engagement)
  ◀────────── {request, nonce: o} ───────
  ── POST /pair/reveal {nonce: t} ───────▶  vérifie H(t) ; ouvre la page locale
  affiche code(F_vu, t, o)                  attend code(F, t, o) sur http://127.0.0.1:3000/
                                            l'utilisateur saisit le code → POST /local/pair
  ── POST /pair/confirm (chaque seconde) ▶  {waiting} puis {token, fingerprint, …}
  vérifie fingerprint = F_vu, épingle F_vu
```

`code = 6 chiffres de SHA-256("unowhy-pair-1\n" + F + "\n" + t + "\n" + o)` (`pairingCode` dans `src/pairing.ts`, `PairingCode.code` sur la tablette ; mêmes valeurs vérifiées par les tests des deux côtés). `F` = empreinte du certificat de la connexion, vide en HTTP local (USB : rien à intercepter ; la tablette épingle alors l'empreinte reçue dans la réponse, pour le WiFi ensuite).

Un intercepteur montre son propre certificat à la tablette : les deux codes diffèrent, l'ordinateur refuse, le jeton n'est jamais donné. L'engagement l'empêche d'essayer des nombres jusqu'à tomber sur le même code : une chance sur un million par appairage, trois essais par code, deux minutes, cinq demandes par adresse et par cinq minutes. La saisie (`/local/pair`) n'est acceptée que depuis la page locale (127.0.0.1, nom d'hôte local, corps JSON).

## Limites connues

- Le WiFi transporte toujours des données lisibles par l'ordinateur lui-même : la protection vaut contre les autres appareils du réseau, pas contre un logiciel malveillant installé sur l'ordinateur.
- Paquets du pavé rejoués : un attaquant qui capte des paquets signés peut les renvoyer depuis sa propre adresse (la séquence est suivie par adresse source) : il ne fait que rejouer des mouvements de souris déjà faits.
- Nouveau certificat (dossier `.data` effacé) : toutes les tablettes doivent être réappairées.
- La recherche WiFi interroge toujours le port 3000 : un compagnon lancé sur un autre `PORT` se saisit à la main (adresse et port HTTPS affichés sur la page locale).

## Tests

Backend : `test/pairing.test.ts` (protocole, intercepteur simulé), `test/tls.test.ts` (certificat, HTTPS, HTTP fermé sur le réseau, appairage en HTTPS), `test/api.test.ts` (appairage en local), `test/pointer*.test.ts`, `e2e/local-page.spec.ts` (saisie du code), contrôle « appairage par code » de `qa/sanity.ts`. Tablette : `PairingCodeTest`, `PinningTest`, `PointerChannelTest`, `ConnectionTest`, `ComputersTest`. Vérifié le 29/10/2026 sur la tablette 4.2 et un Mac : appairage en HTTPS par l'adresse saisie, ancien appairage en clair signalé « à réappairer », Accueil chargé par le canal épinglé.
