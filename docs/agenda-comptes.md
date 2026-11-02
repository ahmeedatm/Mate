# Agenda par connexion au compte (Microsoft, Google)

Sur la page du compagnon (http://127.0.0.1:3000/), carte **Agenda** : « Se connecter avec Microsoft » / « Google ».
L'utilisateur se connecte dans son navigateur, l'agenda arrive sur la tablette dans la minute (réunions récurrentes
dépliées, lien Teams ou Meet pour « Rejoindre »). Le lien ICS reste en repli (« Autre agenda : lien ICS »).

L'utilisateur n'a rien à configurer. **Le projet** enregistre l'appli **une fois** chez Microsoft et chez Google, et livre
les identifiants (publics, pas des secrets) dans `backend/config/accounts.json`. Identifiant vide : bouton masqué.
Le fichier est relu à chaque connexion : pas besoin de redémarrer le compagnon.

## Les mails : une connexion à part

Se connecter pour l'agenda ne donne **aucun droit sur les mails** : la connexion demande `Calendars.Read` (Microsoft) ou
`calendar.readonly` (Google), rien d'autre. Pour le widget **Mails** de la tablette, la page de l'ordinateur propose, sur
chaque compte connecté, **Connecter les mails** (`/oauth/<fournisseur>/start?mail=1`) : une seconde connexion, avec le
même compte, qui ajoute `Mail.ReadBasic` (Microsoft) ou `gmail.metadata` (Google). Le compte garde la trace de ce qu'il a
accordé (`mail: true` dans `.data/accounts.json`, affiché « · mails » sur la page) ; sans elle, `GET /mail/unread` ne le
liste pas et ne l'appelle jamais chez le fournisseur. Reconnecter l'agenda seul retire les droits mails (le nouveau jeton
ne les a pas) ; « Reconnecter » sur un compte aux mails les redemande.

Ces droits permettent de lire l'**expéditeur et l'objet** des mails, **jamais leur contenu** ; le compagnon n'en lit qu'un
nombre (non lus de la boîte de réception) et ne l'envoie à la tablette qu'avec le fournisseur, sans adresse e-mail.

Code : `backend/src/accounts.ts` (connexion OAuth « appli de bureau » : code + PKCE, retour sur localhost),
jetons de renouvellement dans `backend/.data/accounts.json` (sur l'ordinateur, jamais sur la tablette).

## Microsoft (Outlook, Microsoft 365, Outlook.com) : environ 10 minutes

1. Ouvrir **https://portal.azure.com** › rechercher **Inscriptions d'applications** (*App registrations*) › **Nouvelle inscription**.
2. Remplir :
   - **Nom** : `Mate`
   - **Types de comptes pris en charge** : *Comptes dans un annuaire organisationnel (tout locataire Microsoft Entra ID)
     et comptes Microsoft personnels* (Microsoft 365 de n'importe quelle entreprise + Outlook.com).
   - **URI de redirection** : plateforme **Client public/natif (mobile et bureau)**, adresse exacte :
     `http://localhost:3000/oauth/microsoft/callback`
3. **Inscrire**, puis copier l'**ID d'application (client)** (un identifiant `xxxxxxxx-xxxx-…`).
4. **Autorisations d'API** › **Ajouter une autorisation** › **Microsoft Graph** › **Autorisations déléguées** ›
   cocher **Calendars.Read** et **Mail.ReadBasic** (celle-ci n'est demandée qu'à la connexion « mails », voir plus haut) › Ajouter.
   (`User.Read`, déjà présent, peut rester.) Un compte connecté pour l'agenda seul n'a aucun droit sur ses mails.
5. Coller l'identifiant dans `backend/config/accounts.json` :
   ```json
   "microsoft": { "clientId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" }
   ```
   (sur chaque ordinateur, Mac et PC ; en production il est livré avec le logiciel).
6. Tester : http://127.0.0.1:3000/?section=agenda › **Se connecter avec Microsoft**.

Si ça bloque :

| Message | Cause | Solution |
|---|---|---|
| « Vous ne pouvez pas inscrire d'applications » | l'entreprise interdit les inscriptions aux utilisateurs | demander au service informatique, ou inscrire l'appli avec un compte Microsoft personnel (Azure gratuit) : elle marche pour tous les comptes |
| « Approbation de l'administrateur requise » à la connexion | l'entreprise n'autorise pas ses utilisateurs à accepter une appli | un administrateur accepte une fois pour tous (bouton dans *Autorisations d'API*) ; pour un essai, un compte Outlook.com marche |
| `AADSTS7000218` (client_secret demandé) | appli vue comme confidentielle | *Authentification* › **Autoriser les flux clients publics** : Oui |
| `AADSTS50011` (redirect URI) | adresse de retour différente | ajouter exactement l'adresse affichée dans l'erreur (autre port que 3000 : `PORT` dans `.env`) |

## Google (Google Agenda) : facultatif, environ 15 minutes

1. **https://console.cloud.google.com** › nouveau projet `Mate`.
2. **API et services** › **Bibliothèque** › activer **Google Calendar API** et **Gmail API**.
3. **Écran de consentement OAuth** : type **Externe**, nom de l'appli, e-mail ; champ d'application
   `.../auth/calendar.readonly` et `.../auth/gmail.metadata` (celui-ci n'est demandé qu'à la connexion « mails ») ; tant que l'appli est « En test »,
   ajouter les comptes autorisés dans **Utilisateurs test**
   (100 au plus ; `gmail.metadata` est un scope restreint : la publication pour tous demande une vérification par Google).
4. **Identifiants** › **Créer des identifiants** › **ID client OAuth** › type **Application de bureau**.
5. Copier l'ID client et le code secret (pour une appli de bureau, Google le considère comme public) dans
   `backend/config/accounts.json` :
   ```json
   "google": { "clientId": "….apps.googleusercontent.com", "clientSecret": "GOCSPX-…" }
   ```

## Pour la production

- Les applis Microsoft et Google sont enregistrées au nom du projet (éditeur vérifié chez Microsoft, vérification
  Google de l'appli) : plus d'avertissement « appli non vérifiée », plus de limite de 100 comptes Google.
- Les clients d'entreprise dont l'administrateur bloque les applis tierces : consentement administrateur une fois
  pour l'entreprise, ou lien ICS.
