## SQL scripts used for that query and their description

This SQL query analyzes the `refactoring` table within the `refactoring_aware_integration` schema to identify the most frequently occurring types of code refactorings. By grouping entries based on the `refactoring_type` field and counting their occurrences, the query produces a ranked list of the top 20 refactoring types, ordered by descending frequency. 


```sql
SELECT 
    refactoring_type,
    COUNT(*) AS frequency
FROM 
    refactoring_aware_integration.refactoring
GROUP BY 
    refactoring_type
ORDER BY 
    frequency DESC
LIMIT 20;
```


This SQL query evaluates the effectiveness of the RePatch tool in resolving merge conflicts that occur when using Git-CherryPick. Specifically, it selects merge attempts from the `merge_result` table where `Git-CherryPick` produced conflicts (`total_conflicting_files > 0`) and joins them with equivalent attempts using `RePatch` on the same `merge_commit_id`, `project_id`, and `patch_id`. For each project, it computes the total number of conflicting merges encountered with Git-CherryPick (`baseline_failures`) and the number of those conflicts that RePatch was able to resolve successfully without any remaining conflicts (`resolved_by_repatch`). This helps quantify RePatch’s contribution to conflict resolution across projects.

```sql
SELECT 
    git.project_id,
    COUNT(*) AS baseline_failures,
    SUM(CASE WHEN rep.total_conflicting_files = 0 THEN 1 ELSE 0 END) AS resolved_by_repatch
FROM merge_result AS git
JOIN merge_result AS rep 
  ON git.merge_commit_id = rep.merge_commit_id 
  AND git.project_id = rep.project_id 
  AND git.patch_id = rep.patch_id
WHERE git.merge_tool = 'Git-CherryPick'
  AND git.total_conflicting_files > 0
  AND rep.merge_tool = 'RePatch'
GROUP BY git.project_id;
```

This SQL query quantifies how the number of conflicting files changes when using RePatch compared to Git-CherryPick for the same merge attempts. It joins the `merge_result` table on `merge_commit_id`, `project_id`, and `patch_id` to pair equivalent merges handled by both tools. It then categorizes each comparison into one of three outcomes: cases where RePatch resulted in fewer conflicting files (`reduced_conflicts`), more conflicting files (`increased_conflicts`), or no change (`unchanged_conflicts`). These aggregated counts provide an overall picture of RePatch’s impact on conflict reduction relative to Git-CherryPick. **See TABLE III in the paper.**

```sql
SELECT
  COUNT(CASE WHEN rep.total_conflicting_files < git.total_conflicting_files THEN 1 END) AS reduced_conflicts,
  COUNT(CASE WHEN rep.total_conflicting_files > git.total_conflicting_files THEN 1 END) AS increased_conflicts,
  COUNT(CASE WHEN rep.total_conflicting_files = git.total_conflicting_files THEN 1 END) AS unchanged_conflicts
FROM merge_result AS git
JOIN merge_result AS rep 
  ON git.merge_commit_id = rep.merge_commit_id
  AND git.project_id = rep.project_id
  AND git.patch_id = rep.patch_id
WHERE git.merge_tool = 'Git-CherryPick'
  AND rep.merge_tool = 'RePatch';
```

This SQL query compares the extent of merge conflicts—measured by the number of conflicting lines of code—between Git-CherryPick and RePatch for identical merge scenarios in the `merge_result` table. By joining on `merge_commit_id`, `project_id`, and `patch_id`, the query ensures a direct comparison of the same merge instance handled by both tools. It then counts how many times RePatch resulted in fewer (`repatch_fewer_conflicting_loc`), more (`repatch_more_conflicting_loc`), or equal (`conflicting_loc_equal`) lines of conflicting code compared to Git-CherryPick. This breakdown helps assess whether RePatch consistently reduces the complexity of merge conflicts at the line-of-code (LOC) level. **See TABLE III in the paper.**

```sql
SELECT
  COUNT(CASE WHEN rep.total_conflicting_loc < git.total_conflicting_loc THEN 1 END) AS repatch_fewer_conflicting_loc,
  COUNT(CASE WHEN rep.total_conflicting_loc > git.total_conflicting_loc THEN 1 END) AS repatch_more_conflicting_loc,
  COUNT(CASE WHEN rep.total_conflicting_loc = git.total_conflicting_loc THEN 1 END) AS conflicting_loc_equal
FROM refactoring_aware_integration.merge_result AS git
JOIN refactoring_aware_integration.merge_result AS rep 
  ON git.merge_commit_id = rep.merge_commit_id
  AND git.project_id = rep.project_id
  AND git.patch_id = rep.patch_id
WHERE git.merge_tool = 'Git-CherryPick'
  AND rep.merge_tool = 'RePatch';
```

This SQL query provides a summary of merge outcomes for each project fork in the dataset (**See TABLE II in the paper**). It joins the `project` and `patch` tables from the `refactoring_aware_integration` schema to aggregate merge statistics per fork, identified by `fork_name` and `fork_url`. For each fork, it calculates the total number of merge operations (`MO`), and classifies them as either successful (`Passed`) or conflicting (`Failed`) based on the `is_conflicting` flag. The results are ordered in descending order by the number of merge operations, highlighting the most actively evaluated forks.

```sql
SELECT 
    p.fork_name AS Target, p.fork_url AS URL,
    COUNT(pa.id) AS MO,
    SUM(CASE WHEN pa.is_conflicting = 0 THEN 1 ELSE 0 END) AS Passed,
    SUM(CASE WHEN pa.is_conflicting = 1 THEN 1 ELSE 0 END) AS Failed
FROM 
    refactoring_aware_integration.project p
JOIN 
    refactoring_aware_integration.patch pa ON p.id = pa.project_id
GROUP BY 
    p.fork_name, p.fork_url
ORDER BY 
    MO DESC;
```

This SQL query retrieves detailed information about refactoring instances from the `refactoring_aware_integration` schema by linking the `refactoring`, `patch`, and `project` tables. It selects the type of refactoring performed (`refactoring_type`), the associated `project_id` and `patch_id`, and the corresponding project fork name (`fork_name`). By joining the tables through their shared identifiers, the query associates each refactoring instance with its specific project and patch context, providing a foundation for analyzing refactoring activities across different forks. The result can be observed in file: `refactorings_project.csv`

```sql
SELECT refactoring_type, refactoring.project_id, refactoring.patch_id, project.fork_name
FROM refactoring_aware_integration.refactoring, patch, project
WHERE refactoring.patch_id = patch.id AND refactoring.project_id = project.id
```