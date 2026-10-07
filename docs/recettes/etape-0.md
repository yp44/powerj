# Recette — Étape 0 : squelette et exe

**Objectif :** vérifier que la CI produit un `powerj.exe` installable et lançable, avec un REPL minimal.

## Récupérer les livrables

1. Ouvrir l'onglet **Actions** du dépôt GitHub, puis la dernière exécution du workflow **CI** sur la branche à recetter.
2. Dans **Artifacts**, télécharger :
   - `powerj-windows-x64-installer` : l'installeur `powerj-0.1.0.exe` ;
   - `powerj-windows-x64-portable` : la version portable (zip contenant `powerj\powerj.exe`).

## Scénario

| # | Action | Résultat attendu |
|---|---|---|
| 1 | Lancer l'installeur, accepter les choix proposés (installation pour l'utilisateur courant). | L'installation se termine sans droits administrateur. |
| 2 | Ouvrir **PowerJ** depuis le menu Démarrer (dossier *PowerJ*) ou le raccourci du Bureau. | Une console s'ouvre avec la bannière `PowerJ 0.1.0-SNAPSHOT (Java 27)` puis le prompt `PJ C:\…> `. |
| 3 | Taper `bonjour` puis Entrée. | Message `commande inconnue : bonjour`, puis un nouveau prompt. |
| 4 | Appuyer sur Entrée sur une ligne vide. | Un nouveau prompt, sans message. |
| 5 | Taper `exit trois`. | Message `exit : code retour invalide 'trois'`. |
| 6 | Taper `exit`. | La fenêtre se ferme. |
| 7 | Version portable : dézipper l'archive, ouvrir `cmd.exe` dans le dossier `powerj`, taper `powerj.exe`. | Bannière et prompt indiquant le dossier courant de `cmd.exe`. |
| 8 | Dans PowerJ, taper `exit 3`, puis dans `cmd.exe` taper `echo %ERRORLEVEL%`. | Affiche `3`. |

## Limites connues de l'étape 0

- Pas encore d'historique, de flèches ni de Ctrl+R (étape 1) : la saisie est une lecture de ligne simple.
- L'installeur n'ajoute pas encore PowerJ au `PATH` : jpackage ne le propose pas nativement, ce sera traité dans une étape ultérieure (personnalisation WiX). En attendant, lancer PowerJ depuis le menu Démarrer ou par son chemin complet.
- Pas d'icône personnalisée.
