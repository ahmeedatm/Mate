# Mot de réveil « Mate » — conception

29/10/2026 · Bruno · branche `feat/mot-de-reveil` (depuis `origin/main` f4de8b9)

## Pourquoi

Déclencher la voix sans toucher l'écran : « Mate… ouvre Chrome », « Mate… ajoute-moi un rappel ». Prévu en phase 2
(README : « mot de réveil : à faire »). Le mot de réveil remplace l'appui sur « Parler » ; la suite (reconnaissance
Android, `VoiceCommands`, exécution) ne change pas.

## Ce que Bruno a décidé

- Le mot est **« Mate »** (« Hey Mate » / « Hé Mate » aussi).
- Proposition de Claude acceptée :
  - à l'écoute seulement quand Mate est à l'écran et l'écran allumé ; rien en arrière-plan ;
  - reconnaissance du mot sur la tablette, hors ligne ; rien ne part avant « Mate » ;
  - un voyant toujours visible quand la tablette écoute, qui s'allume franchement quand elle a entendu « Mate » ;
  - pause automatique quand le micro sert déjà (commande vocale en cours, dictée du presse-papiers, caméra, appel) ;
  - interrupteur « Mot de réveil » dans Réglages, **activé par défaut**.
- Pas d'essai jetable : code direct.

## Non-objectifs

- Mot choisi par l'utilisateur, plusieurs mots, écoute écran éteint ou appli en arrière-plan.
- Reconnaître la commande elle-même hors ligne (elle reste à la reconnaissance Android, en ligne, comme « Parler »).
- Réglage de sensibilité.

## Choix techniques (Claude)

- **Moteur : Vosk** (`com.alphacephei:vosk-android`, Apache 2.0, hors ligne, sans clé ni compte), petit modèle français
  `vosk-model-small-fr-0.22`, en grammaire restreinte : `["mate", "hé mate", "hey mate", "[unk]"]` (tout le reste tombe
  dans `[unk]`). Détection : résultat final dont les mots contiennent « mate » avec une confiance ≥ 0,7 (réglable en
  constante après essai). Porcupine écarté (clé Picovoice vérifiée en ligne, offre gratuite arrêtée en 2026) ;
  openWakeWord écarté (modèle « hey mate » à entraîner soi-même).
- **Modèle** (~40 Mo) : pas dans l'APK (chaque mise à jour le retéléchargerait). Téléchargé une fois, à la première
  activation, depuis `https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip`, empreinte SHA-256 vérifiée
  (constante dans le code), décompressé dans les fichiers de l'appli. Progression et erreurs dans Réglages.
  Téléchargement raté : réessayé au prochain passage à l'écran, sans message bloquant.
- **Tablette 4.2** : Android 10, MediaTek MT8167 (ARM 64 bits), 2 Go, un micro (bas). Écoute en 16 kHz mono,
  `SpeechService` de Vosk (fil à part).
- **Micro partagé** : Android 10 ne donne le micro qu'à une écoute. À « Mate » : l'écoute Vosk s'arrête, un bip court
  (`ToneGenerator`) et le voyant confirment, puis `VoiceControl.start()` comme un appui sur « Parler » ; l'écoute du
  mot reprend quand la commande vocale est finie. Même pause pendant la dictée du presse-papiers, la caméra et un
  appel affiché (carte d'appel).
- **Phrase d'une traite** (« Mate ouvre Chrome ») : la reconnaissance Android démarre après le mot ; ce qui est dit
  avant son ouverture peut être perdu. Usage conseillé : « Mate », bip, puis la demande. À revoir après essai.
- **Voyant** : petite icône micro à côté de la cloche des notifications, en haut à gauche ; atténuée = écoute le mot,
  couleur d'accent = « Mate » entendu / commande en cours, absente = mot de réveil coupé ou en pause.
- **Permission micro** : celle de « Parler » ; refusée → pas d'écoute, explication dans Réglages.

## Réussite

1. « Mate » à 1 m puis à 3 m, tablette posée sur le bureau : bip, puis « ouvre Chrome » ouvre Chrome sur le PC.
2. Une conversation normale de 5 minutes à côté ne déclenche pas (ou très rarement).
3. Charge processeur de l'appli raisonnable pendant l'écoute (à mesurer : `top` par adb).
4. Interrupteur coupé : plus aucune écoute, voyant absent.
5. Tests JVM : détection (lecture du JSON de Vosk, seuil), état de l'écoute (pause / reprise).
