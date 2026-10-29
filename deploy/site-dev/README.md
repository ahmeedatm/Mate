# Site vitrine Mate sur le cluster dev

https://mate.development.unowhy.dev : le site de `site/` et les installeurs de la dernière release, dans une image nginx.

## Ce que fait la CI

Jobs `site-downloads` puis `site-image` (`.gitlab-ci.yml`, stage `site`) :

| Pipeline | Image |
|---|---|
| MR qui touche `site/` | construite, pas poussée (vérifie le Dockerfile) |
| main, si `site/` change | `harbor.unowhy.dev/squads/mate-site:main` et `:<commit>` |
| tag `vX.Y.Z` | `:main`, `:<commit>` et `:vX.Y.Z`, avec les installeurs de ce tag |

Les installeurs viennent du paquet `companion-updates/latest` du registre (`scripts/site-downloads.mjs`, taille et SHA-256 vérifiés). La CI ne déploie pas : le cluster suit le tag `main` avec Keel (annotations du Deployment, comme les fronts de `sqool-deploy`).

## Déployer

```bash
kubectl apply -k deploy/site-dev
kubectl -n mate rollout status deployment/mate-site
```

Ensuite, chaque image `main` poussée est reprise par Keel dans la minute. Pour voir ce que donnent les manifestes sans rien appliquer : `kubectl kustomize deploy/site-dev`.

## À demander une fois à l'équipe plateforme

1. **Harbor** : le projet `squads/mate-site`, et un compte robot qui peut y pousser ; ses identifiants dans les variables CI du projet, masquées et protégées : `HARBOR_REGISTRY_USERNAME`, `HARBOR_REGISTRY_PASSWORD` (les mêmes noms que pour les squads).
2. **Namespace** `mate` sur le cluster dev, avec l'injection Istio comme les autres namespaces des squads.
3. **Secret de tirage** `harbor-squads-mate-site-registry-credential` dans ce namespace (type `kubernetes.io/dockerconfigjson`, lecture de `squads/mate-site`) : jamais dans ce dépôt.
4. **Passerelle Istio** : le VirtualService utilise `devops-core/devops-unowhy-gateway`, celle des hôtes `*.unowhy.dev` de `sqool-deploy` ; confirmer qu'elle accepte `mate.development.unowhy.dev` (certificat compris), sinon changer `gateways` dans `virtualservice.yaml`. Le DNS est créé par external-dns (annotation du VirtualService).
5. **Runner** : `site-image` construit en docker-in-docker (service `docker:27.5.1-dind` du proxy Harbor, comme `sqool-deploy`), donc sur un runner qui l'autorise.

## Fichiers

| Fichier | Rôle |
|---|---|
| `kustomization.yaml` | namespace `mate`, étiquettes, image `harbor.unowhy.dev/squads/mate-site:main` |
| `deployment.yaml` | 1 réplique, nginx sans root (101), système de fichiers en lecture seule, sondes `/healthz`, Keel |
| `service.yaml` | port 80 vers le 8080 de nginx |
| `virtualservice.yaml` | `mate.development.unowhy.dev`, passerelle Istio, external-dns |
