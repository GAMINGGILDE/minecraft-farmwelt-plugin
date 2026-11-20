package de.minecraftgilde.farmwelt.config;

import de.minecraftgilde.farmwelt.reset.FarmworldType;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/** Prüft die Originalschlüssel, bevor Bukkit gleich geschriebene YAML-Einträge zusammenfasst. */
public final class FarmworldYamlKeys {
    private FarmworldYamlKeys() {}

    public static MappingNode section(Node root, String sectionName) {
        if (!(root instanceof MappingNode mapping)) {
            return null;
        }
        MappingNode result = null;
        boolean found = false;
        for (NodeTuple entry : entries(mapping)) {
            String name = key(entry);
            if (name.startsWith(sectionName + ".")) {
                throw new IllegalArgumentException("YAML-Pfad '" + name
                        + "' muss unter dem eindeutigen Bereich '" + sectionName + "' stehen.");
            }
            if (sectionName.equals(name)) {
                if (found) {
                    throw new IllegalArgumentException("Doppelter YAML-Bereich '" + sectionName + "'.");
                }
                found = true;
                result = entry.getValueNode() instanceof MappingNode child ? child : null;
            }
        }
        return result;
    }

    public static Map<FarmworldType, List<NodeTuple>> duplicates(String contents, String sectionName) {
        Node root = new Yaml().compose(new StringReader(contents));
        MappingNode section = section(root, sectionName);
        if (section == null) {
            return Map.of();
        }
        var groups = new EnumMap<FarmworldType, List<NodeTuple>>(FarmworldType.class);
        for (NodeTuple entry : entries(section)) {
            String name = key(entry);
            int dot = name.indexOf('.');
            if (dot > 0 && FarmworldType.fromFarmworldKey(name.substring(0, dot)).isPresent()) {
                throw new IllegalArgumentException("Mehrdeutiger YAML-Pfad '" + name
                        + "'. Jede Farmwelt-ID benötigt einen eigenen Bereich.");
            }
            FarmworldType.fromFarmworldKey(name).ifPresent(type ->
                    groups.computeIfAbsent(type, ignored -> new ArrayList<>()).add(entry));
        }
        groups.values().removeIf(entries -> entries.size() < 2);
        return groups;
    }

    private static List<NodeTuple> entries(MappingNode mapping) {
        if (!mapping.isMerged()) {
            return mapping.getValue();
        }
        // Vor dem YAML-Merge zählen auch über Anker übernommene ID-Deklarationen.
        var pending = new ArrayDeque<Node>();
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var result = new ArrayList<NodeTuple>();
        pending.add(mapping);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (!visited.add(node)) {
                continue;
            }
            if (node instanceof SequenceNode sequence) {
                pending.addAll(sequence.getValue());
            } else if (node instanceof MappingNode source) {
                for (NodeTuple entry : source.getValue()) {
                    if (Tag.MERGE.equals(entry.getKeyNode().getTag())) {
                        pending.add(entry.getValueNode());
                    } else {
                        result.add(entry);
                    }
                }
            }
        }
        return result;
    }

    public static String diagnosis(List<NodeTuple> entries) {
        return "Mehrdeutige Farmwelt-ID: " + entries.stream().map(FarmworldYamlKeys::key).toList() + ".";
    }

    private static String key(NodeTuple tuple) {
        return tuple.getKeyNode() instanceof ScalarNode key ? key.getValue() : "";
    }
}
