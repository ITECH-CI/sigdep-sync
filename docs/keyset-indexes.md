# Index recommandés pour la pagination keyset (base source OpenMRS)

Les extracteurs de l'agent sync paginent en **keyset composite** `(date, id)` :

```sql
WHERE  date_expr > :lastDate
   OR (date_expr = :lastDate AND id > :lastId)
ORDER BY date_expr, id
LIMIT :batchSize
```

Pour que cette pagination reste efficace (et ne dégénère pas en *full scan*
+ *filesort* à chaque page, surtout pendant un backfill de masse), la base
**source OpenMRS/MySQL** doit porter un index couvrant le couple
`(date_de_tri, pk)` sur chaque table paginée.

> ⚠️ Ces index sont sur la base **source** (OpenMRS), pas sur le buffer SQLite
> de l'agent ni sur le hub. Ils ne sont pas créés par l'agent (lecture seule) :
> c'est une opération à faire côté OpenMRS. Les tables volumineuses
> (`encounter`, `obs`) en bénéficient le plus.

## Index par table

| Table source | Colonne(s) de tri | Tie-breaker (PK) | Index recommandé |
|---|---|---|---|
| `encounter` | `COALESCE(date_changed, date_created)` | `encounter_id` | `(date_changed, encounter_id)` |
| `hiv_screening_hiv_screening` | `screening_date` | `hiv_screening_id` | `(screening_date, hiv_screening_id)` |
| `person` | `COALESCE(date_changed, date_created)` | `person_id` | `(date_changed, person_id)` |
| `patient` | `COALESCE(date_changed, date_created)` | `patient_id` | `(date_changed, patient_id)` |
| `ptme_pregnant_patient` | `COALESCE(date_changed, date_created)` | `pregnant_patient_id` | `(date_changed, pregnant_patient_id)` |
| `ptme_child` | `COALESCE(date_changed, date_created)` | `child_id` | `(date_changed, child_id)` |
| `ptme_mother_followup_visit` | `COALESCE(date_changed, date_created)` | `mother_followup_visit_id` | `(date_changed, mother_followup_visit_id)` |
| `ptme_child_followup_visit` | `COALESCE(date_changed, date_created)` | `child_followup_visit_id` | `(date_changed, child_followup_visit_id)` |

Notes :
- **`encounter`** est paginée par 5 extracteurs (initiations, visites, clôtures,
  TPT, résultats labo), tous filtrés en plus par `encounter_type`. Un index
  `(encounter_type, date_changed, encounter_id)` est encore meilleur si les
  requêtes filtrent d'abord sur le type — à arbitrer selon le plan d'exécution.
- **`person` / `patient`** : `PatientExtractor` trie sur
  `GREATEST(person.date_changed, patient.date_changed)` avec `person_id` comme
  tie-breaker. L'index le plus utile est sur la colonne qui domine le
  `GREATEST` dans les données réelles ; à défaut, indexer les deux.
- Le tie-breaker est toujours une **PK** (unique), ce qui garantit un ordre
  total stable — condition nécessaire pour que le keyset ne saute ni ne rejoue
  aucune ligne au sein d'un groupe de timestamps identiques.

## DDL indicatif (MySQL, à adapter aux noms réels du schéma)

```sql
CREATE INDEX idx_sigdep_encounter_keyset
  ON encounter (date_changed, encounter_id);

CREATE INDEX idx_sigdep_screening_keyset
  ON hiv_screening_hiv_screening (screening_date, hiv_screening_id);

CREATE INDEX idx_sigdep_person_keyset
  ON person (date_changed, person_id);

CREATE INDEX idx_sigdep_patient_keyset
  ON patient (date_changed, patient_id);

CREATE INDEX idx_sigdep_ptme_mother_keyset
  ON ptme_pregnant_patient (date_changed, pregnant_patient_id);

CREATE INDEX idx_sigdep_ptme_child_keyset
  ON ptme_child (date_changed, child_id);

CREATE INDEX idx_sigdep_ptme_mother_visit_keyset
  ON ptme_mother_followup_visit (date_changed, mother_followup_visit_id);

CREATE INDEX idx_sigdep_ptme_child_visit_keyset
  ON ptme_child_followup_visit (date_changed, child_followup_visit_id);
```

## État actuel

À ce jour, **aucun de ces index n'est garanti présent** sur les bases OpenMRS
des sites : OpenMRS crée un index sur les PK et sur certains `date_changed`
isolés, mais **pas** sur le couple `(date_changed, pk)`. Sans eux, la
pagination reste correcte (le keyset garantit l'exactitude) mais peut être
lente sur les grosses tables. À poser lors de la préparation d'un site à fort
volume, en particulier après une migration de masse.
