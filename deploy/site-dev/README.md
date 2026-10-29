# Site vitrine Mate sur le cluster dev

https://mate.integration.unowhy.dev : le site de `site/` et les installeurs de la dernière release, dans une image nginx.

## Ce que fait la CI

Jobs `site-downloads` puis `site-image` (`.gitlab-ci.yml`, stage `site`) :

| Pipeline | Image |
|---|---|
| MR qui touche `site/` | `:<branche>` (ex. `:feat-site-image`) et `:<commit>`, jamais `:main` |
| main, si `site/` change | `harbor.unowhy.dev/squads/mate-site:main` et `:<commit>` |
| tag `vX.Y.Z` | `:main`, `:<commit>` et `:vX.Y.Z`, avec les installeurs de ce tag |

Les installeurs viennent du paquet `companion-updates/latest` du registre (`scripts/site-downloads.mjs`, taille et SHA-256 vérifiés). La CI ne déploie pas : le cluster suit le tag `main` avec Keel (annotations du Deployment, comme les fronts de `sqool-deploy`).

## Déployer

```bash
kubectl apply -k deploy/site-dev
kubectl -n mate rollout status deployment/mate-site
```

Ensuite, chaque image `main` poussée est reprise par Keel dans la minute. Pour voir ce que donnent les manifestes sans rien appliquer : `kubectl kustomize deploy/site-dev`.

Essayer l'image d'une MR sans fusionner : `kubectl -n mate set image deployment/mate-site site=harbor.unowhy.dev/squads/mate-site:<branche>`, puis revenir avec `kubectl apply -k deploy/site-dev` (Keel ne suit que `:main`).

## À demander une fois à l'équipe plateforme

1. **Harbor** : le projet `squads/mate-site`, et un compte robot qui peut y pousser ; ses identifiants dans les variables CI du projet, masquées et protégées : `HARBOR_REGISTRY_USERNAME`, `HARBOR_REGISTRY_PASSWORD` (les mêmes noms que pour les squads).
2. ~~**Namespace** `mate`~~ : créé le 29/10/2026 (`namespace.yaml`). Il lui faut les étiquettes des squads : `istio.io/rev` (révision Istio) et les trois `hisqool.dev/*` que suit istiod (sans elles, pas de certificat Istio dans le namespace et le pod reste bloqué à l'initialisation).
3. ~~**Secret de tirage**~~ `harbor-squads-mate-site-registry-credential` : créé le 29/10/2026 dans `mate`, copie de celui de `powerbank-front-admin` (même compte robot `robot-squads`, lecture de tout le projet `squads`). Jamais dans ce dépôt ; pour le recréer, le recopier depuis un autre namespace des squads.
4. ~~**Passerelle Istio**~~ : `devops-core/devops-hisqool-gateway`, la seule du cluster `hisqool-dev` qui accepte `*.integration.unowhy.dev` avec son certificat (comme `power.integration.unowhy.dev`) ; aucune n'y sert `*.development.unowhy.dev`. Le DNS est créé par external-dns (annotation du VirtualService), environ 2 min après le premier `apply`.
5. **Runner** : `site-image` construit en docker-in-docker (service `docker:27.5.1-dind` du proxy Harbor, comme `sqool-deploy`), donc sur un runner qui l'autorise.

## Fichiers

| Fichier | Rôle |
|---|---|
| `kustomization.yaml` | namespace `mate`, étiquettes, image `harbor.unowhy.dev/squads/mate-site:main` |
| `namespace.yaml` | namespace `mate` et ses étiquettes Istio |
| `deployment.yaml` | 1 réplique, nginx sans root (101), système de fichiers en lecture seule, sondes `/healthz`, Keel, proxy Istio demandé (`sidecar.istio.io/inject`, la politique du cluster est « disabled ») |
| `service.yaml` | port 80 vers le 8080 de nginx |
| `virtualservice.yaml` | `mate.integration.unowhy.dev`, passerelle Istio, external-dns |
