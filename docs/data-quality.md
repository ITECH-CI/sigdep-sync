# Qualité des données SIGDEP-3 (source OpenMRS)

Ce document recense les **problèmes de qualité de données** observés dans les
bases OpenMRS des sites lors de la synchronisation vers le hub SIGDEP-3, leur
**impact**, le **correctif logiciel** appliqué côté agent/hub, et les
**nettoyages de base recommandés** avant ou pendant un déploiement.

Il a deux usages :

1. **Présentation aux parties prenantes** — donner à voir l'état réel de la
   donnée saisie sur le terrain et les décisions prises pour la fiabiliser.
2. **Référence opérationnelle** — chaque problème est accompagné d'une requête
   de détection réutilisable, pour auditer une base avant de brancher son agent.

> Les requêtes « source » s'exécutent sur la base **MySQL OpenMRS** du site.
> Les requêtes « hub » s'exécutent sur le **PostgreSQL** du hub
> (`audit.rejected_record` trace tous les rejets d'ingestion).

---

## Vue d'ensemble : lire les rejets du hub

Le hub journalise chaque enregistrement refusé dans `audit.rejected_record`
(`error_code`, `error_message`, `entity_type`, `site_id`, `resolved_at`). C'est
le **point d'entrée du diagnostic qualité** : un pic de rejets sur un site
révèle presque toujours un problème de donnée source.

```sql
-- Ventilation des rejets ouverts, par code et entité
SELECT error_code, entity_type, COUNT(*) AS nb
FROM audit.rejected_record
WHERE resolved_at IS NULL
GROUP BY error_code, entity_type
ORDER BY nb DESC;
```

Deux familles de rejets, à ne pas confondre :

| Code | Nature | Se résout seul ? |
|------|--------|------------------|
| `UNKNOWN_PATIENT` | **Dépendance** : l'enregistrement référence un patient absent au hub | Oui **si** décalage d'ordonnancement (le patient arrive au cycle suivant) ; **non** si le patient n'existera jamais (voir DQ-01) |
| `UPSERT_FAILED` | **Validation** : la donnée elle-même viole une contrainte (taille, type, format) | Non — nécessite un correctif logiciel et/ou un nettoyage source |

---

## Problèmes recensés

### DQ-01 — `person` sans `patient` (encounters orphelins)

**Symptôme.** Des milliers de rejets `UNKNOWN_PATIENT` (« Patient `<uuid>` not
yet ingested »), rejoués à chaque cycle **indéfiniment**, tous sur un même
`person_id`. Observé en prod : ~82 000 rejets (closures, lab_results,
treatment_initiations) sur un seul patient au site 02344.

**Cause racine.** Dans OpenMRS, la ligne existe dans `person` mais **pas** dans
`patient` (personne créée comme relation, prestataire, contact… ou patient dont
la ligne `patient` a été supprimée en gardant la `person`). Des encounters
pointent pourtant vers ce `person_id`. L'agent émettait alors ces encounters
avec un UUID que l'extracteur de patients (qui exige `FROM patient JOIN person`)
n'extrait jamais → le hub les rejette sans fin.

**Correctif logiciel.** *(agent v2.2.1)* Les extracteurs d'encounters
(`Closure`, `LabResult`, `Initiation`, `Tpt` ; `Visit` le faisait déjà) exigent
désormais un `JOIN patient` : un encounter rattaché à une `person` non-`patient`
n'est plus émis.

**Détection (source OpenMRS).**
```sql
-- Encounters rattachés à une person qui n'est PAS un patient
SELECT e.encounter_id, e.uuid, per.uuid AS person_uuid, et.name AS type
FROM encounter e
JOIN encounter_type et ON et.encounter_type_id = e.encounter_type
JOIN person per ON per.person_id = e.patient_id
LEFT JOIN patient pat ON pat.patient_id = e.patient_id
WHERE pat.patient_id IS NULL
  AND e.voided = 0;
```

**Nettoyage source recommandé.** Investiguer chaque `person_id` orphelin :
- si c'est une **vraie personne mal typée** (devrait être patient) → créer la
  ligne `patient` manquante dans OpenMRS ;
- si les **encounters sont orphelins** (personne inexistante côté métier) →
  voider les encounters concernés.

**Remise en état hub.** Résoudre les rejets `UNKNOWN_PATIENT` du patient
concerné une fois l'agent ≥ v2.2.1 déployé **et** les lignes déjà bufferisées
purgées (statut `DEAD_LETTER` côté agent). Sinon les rejets se recréent.

---

### DQ-02 — Valeurs texte polluées par du padding d'espaces

**Symptôme.** Rejets `UPSERT_FAILED` en boucle sur un même enregistrement :
`value too long for type character varying(255)` sur
`core.patients.birth_place`. Observé au site 22 : 1 record rejeté 90 fois.

**Cause racine.** OpenMRS stocke des `obs.value_text` **noyés dans des espaces**.
Constaté en base : `"ABOBO"` suivi de ~300 espaces (**313 caractères**), et une
valeur **entièrement blanche de 1988 caractères**. La vraie donnée fait 5
caractères ; le padding la fait déborder de la colonne cible du hub.

**Correctif logiciel.** *(agent v2.2.2)* `ObsPivot` normalise les valeurs texte
(`value_text`, `coded_name`) : `strip()` systématique ; une valeur devenue vide
est traitée comme **absente** (`null`) — ce qui laisse le `COALESCE` du hub
préserver une éventuelle valeur existante au lieu de l'écraser par du blanc.

**Détection (source OpenMRS).**
```sql
-- Obs texte dont la valeur trimée est bien plus courte que la valeur brute
-- (padding), ou entièrement blanche
SELECT o.obs_id, c.uuid AS concept_uuid,
       LENGTH(o.value_text)          AS longueur_brute,
       LENGTH(TRIM(o.value_text))    AS longueur_utile,
       TRIM(o.value_text)            AS valeur
FROM obs o
JOIN concept c ON c.concept_id = o.concept_id
WHERE o.voided = 0
  AND o.value_text IS NOT NULL
  AND LENGTH(o.value_text) <> LENGTH(TRIM(o.value_text))
ORDER BY longueur_brute DESC
LIMIT 100;
```

**Nettoyage source recommandé.** Optionnel (le correctif agent suffit à la
synchro), mais utile pour la qualité locale : trimmer les `value_text` en base,
et former les agents de saisie à ne pas coller de texte dans les mauvais champs.

---

## Gabarit pour un nouveau problème (DQ-NN)

> **Symptôme** — ce qu'on observe (rejets, code d'erreur, volume, site).
> **Cause racine** — la caractéristique de la donnée source responsable.
> **Correctif logiciel** — version agent/hub qui l'adresse (si applicable).
> **Détection** — requête réutilisable (source ou hub).
> **Nettoyage source recommandé** — action sur la base OpenMRS.
> **Remise en état hub** — comment résoudre les rejets déjà accumulés.

---

## Check-list d'audit qualité avant déploiement d'un site

À passer sur la base OpenMRS d'un site **avant** de brancher son agent, pour
anticiper les rejets :

1. **Encounters orphelins** (DQ-01) — requête de détection ci-dessus. Si > 0,
   décider création `patient` vs void des encounters.
2. **Padding de texte** (DQ-02) — requête de détection ci-dessus. Informatif ;
   le correctif agent gère, mais un gros volume signale une saisie à corriger.
3. **Volume par entité** — comparer l'ordre de grandeur attendu vs réel
   (l'agent expose `--reconcile` pour un rapport local par `entity_type`).

Après branchement, surveiller `audit.rejected_record` les premiers cycles :
tout `UPSERT_FAILED` est un signal qualité à tracer ici comme un nouveau DQ-NN.
