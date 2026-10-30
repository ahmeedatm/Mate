# Agenda par connexion au compte (Microsoft, Google)

Sur la page du compagnon (http://127.0.0.1:3000/), carte **Agenda** : « Se connecter avec Microsoft » / « Google ».
L'utilisateur se connecte dans son navigateur, l'agenda arrive sur la tablette dans la minute (réunions récurrentes
dépliées, lien Teams ou Meet pour « Rejoindre »). Le lien ICS reste en repli (« Autre agenda : lien ICS »).

Le client n'a rien à configurer. **Unowhy** enregistre l'appli **une fois** chez Microsoft et chez Google, et livre
les identifiants (publics, pas des secrets) dans `backend/config/accounts.json`. Identifiant vide : bouton masqué.
Le fichier est relu à chaque connexion : pas besoin de redémarrer le compagnon.

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
   cocher **Calendars.Read** › Ajouter. (`User.Read`, déjà présent, peut rester.)
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
| « Approbation de l'administrateur requise » à la connexion | l'entreprise n'autorise pas ses utilisateurs à accepter une appli | un administrateur accepte une fois pour tous (bouton dans *Autorisations d'API*) ; pour une démo, un compte Outlook.com marche |
| `AADSTS7000218` (client_secret demandé) | appli vue comme confidentielle | *Authentification* › **Autoriser les flux clients publics** : Oui |
| `AADSTS50011` (redirect URI) | adresse de retour différente | ajouter exactement l'adresse affichée dans l'erreur (autre port que 3000 : `PORT` dans `.env`) |

## Google (Google Agenda) : facultatif, environ 15 minutes

1. **https://console.cloud.google.com** › nouveau projet `Mate`.
2. **API et services** › **Bibliothèque** › activer **Google Calendar API**.
3. **Écran de consentement OAuth** : type **Externe**, nom de l'appli, e-mail ; champ d'application
   `.../auth/calendar.readonly` ; tant que l'appli est « En test », ajouter les comptes autorisés dans **Utilisateurs test**
   (100 au plus ; la publication pour tous demande une vérification par Google).
4. **Identifiants** › **Créer des identifiants** › **ID client OAuth** › type **Application de bureau**.
5. Copier l'ID client et le code secret (pour une appli de bureau, Google le considère comme public) dans
   `backend/config/accounts.json` :
   ```json
   "google": { "clientId": "….apps.googleusercontent.com", "clientSecret": "GOCSPX-…" }
   ```

## Pour la production

- Les applis Microsoft et Google sont enregistrées au nom d'Unowhy (éditeur vérifié chez Microsoft, vérification
  Google de l'appli) : plus d'avertissement « appli non vérifiée », plus de limite de 100 comptes Google.
- Les clients d'entreprise dont l'administrateur bloque les applis tierces : consentement administrateur une fois
  pour l'entreprise, ou lien ICS.
