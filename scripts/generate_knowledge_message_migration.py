"""Build V67 as a narrow patch of the knowledge graph, preserving settings and unrelated edits."""
import json
import re
from pathlib import Path

root = Path('src/main/resources')
old = next(g for s in re.findall(r'\$graph\$\s*(.*?)\s*\$graph\$::jsonb', (root/'db/migration/V64__transparent_automation_graphs.sql').read_text(), re.S)
           if (g := json.loads(s))['entryNodeId'] == 'knowledge-1')
new = json.loads((root/'automation/templates/knowledge.json').read_text())
old_nodes = {n['id']: n for n in old['nodes']}
new_ids = {n['id'] for n in new['nodes']}

def difference(before, after):
    return {k: difference(before[k], v) if isinstance(v, dict) and isinstance(before.get(k), dict) else v
            for k, v in after.items() if before.get(k) != v}

patch = dict(updates={n['id']: difference(old_nodes[n['id']], n) for n in new['nodes'] if n['id'] in old_nodes},
             addNodes=[n for n in new['nodes'] if n['id'] not in old_nodes],
             removeNodeIds=[n['id'] for n in old['nodes'] if n['id'] not in new_ids],
             replaceEdgeSources=list(old_nodes), edges=new['edges'])
sql = '''-- Freeze the incoming message and discard obsolete knowledge hints in the editable graph.
-- Preserve thresholds, enabled state, folder, custom fields and unrelated nodes. Back up before changing.
do $migration$
declare
    patch jsonb := $patch$
PATCH_JSON
$patch$::jsonb;
    target record;
    graph jsonb;
    node jsonb;
    delta jsonb;
    config jsonb;
    nodes jsonb;
    edges jsonb;
begin
    for target in select * from automation_trigger
        where workflow_definition is not null
          and workflow_definition::jsonb->>'entryNodeId' = 'knowledge-1'
          and not exists (select 1 from jsonb_array_elements(workflow_definition::jsonb->'nodes') n
                          where n->>'id' = 'knowledge-source-message')
        for update
    loop
        insert into automation_workflow_backup(trigger_id,migration_version,definition)
            values(target.id,67,to_jsonb(target)) on conflict do nothing;
        graph := target.workflow_definition::jsonb;
        select coalesce(jsonb_agg(e), '[]'::jsonb) into edges
            from jsonb_array_elements(graph->'edges') e
            where not (patch->'replaceEdgeSources' ? (e->>'source'));
        edges := edges || (patch->'edges');
        nodes := '[]'::jsonb;
        for node in select * from jsonb_array_elements(graph->'nodes')
        loop
            if patch->'removeNodeIds' ? (node->>'id') and not exists
                (select 1 from jsonb_array_elements(edges) e where e->>'target' = node->>'id' or e->>'source' = node->>'id')
            then continue; end if;
            delta := patch->'updates'->(node->>'id');
            if delta is not null then
                config := coalesce(node->'config','{}'::jsonb) || coalesce(delta->'config','{}'::jsonb);
                if delta#>'{config,arguments}' is not null then
                    config := jsonb_set(config,'{arguments}',coalesce(node#>'{config,arguments}','{}'::jsonb) || (delta#>'{config,arguments}'));
                end if;
                node := node || (delta - 'config') || jsonb_build_object('config',config);
            end if;
            nodes := nodes || jsonb_build_array(node);
        end loop;
        graph := graph || jsonb_build_object('nodes',nodes || (patch->'addNodes'),'edges',edges);
        update automation_trigger set workflow_definition=graph::text where id=target.id;
    end loop;
end
$migration$;
'''.replace('PATCH_JSON', json.dumps(patch, ensure_ascii=False, separators=(',', ':')))
(root/'db/migration/V67__knowledge_hints_latest_message.sql').write_text(sql)
