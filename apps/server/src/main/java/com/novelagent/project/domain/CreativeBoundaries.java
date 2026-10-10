package com.novelagent.project.domain;

import java.util.HashMap;
import java.util.Map;

/** Project choices and the evidence-backed, reusable source assessment; neither grants canon changes. */
public record CreativeBoundaries(WorldMode worldMode, AdaptationScope adaptationScope,
        WorldMode assessedWorld, String reason, String sourceHash, String sourceImportId) {
    public enum WorldMode { AUTO, REALISTIC, REALISTIC_FICTION, ALTERNATE }
    public enum AdaptationScope { AUTO, PRESERVE_CORE, RESTRUCTURE }
    public static final String KEY = "creativeBoundaries";

    public CreativeBoundaries {
        worldMode = worldMode == null ? WorldMode.AUTO : worldMode;
        adaptationScope = adaptationScope == null ? AdaptationScope.AUTO : adaptationScope;
        if (assessedWorld == WorldMode.AUTO) throw new IllegalArgumentException("自动判断结果不能是 AUTO");
        reason = reason == null ? "" : reason;
    }

    public WorldMode effectiveWorld() {
        return worldMode != WorldMode.AUTO ? worldMode : assessedWorld == null ? WorldMode.REALISTIC : assessedWorld;
    }

    public AdaptationScope effectiveScope() {
        // An inferred genre is not human authorization to replace the main plot.
        return adaptationScope == AdaptationScope.AUTO ? AdaptationScope.PRESERVE_CORE : adaptationScope;
    }

    public static CreativeBoundaries from(NovelProject project) {
        if (!(project.getSetting(KEY) instanceof Map<?, ?> data)) return defaults();
        return new CreativeBoundaries(enumeration(data, "worldMode", WorldMode.class, WorldMode.AUTO),
                enumeration(data, "adaptationScope", AdaptationScope.class, AdaptationScope.AUTO),
                enumeration(data, "assessedWorld", WorldMode.class, null), string(data, "reason"),
                string(data, "sourceHash"), string(data, "sourceImportId"));
    }

    public static CreativeBoundaries defaults() { return new CreativeBoundaries(null, null, null, "", "", ""); }

    public CreativeBoundaries choose(WorldMode world, AdaptationScope scope, boolean reset) {
        return new CreativeBoundaries(world, scope, reset ? null : assessedWorld, reset ? "" : reason,
                reset ? "" : sourceHash, reset ? "" : sourceImportId);
    }

    public CreativeBoundaries assess(WorldMode world, String why, String hash, String importId) {
        return new CreativeBoundaries(worldMode, adaptationScope, world, why, hash, importId);
    }

    public void applyTo(NovelProject project) {
        var values = new HashMap<String, Object>();
        values.put("worldMode", worldMode.name()); values.put("adaptationScope", adaptationScope.name());
        if (assessedWorld != null) values.put("assessedWorld", assessedWorld.name());
        values.put("reason", reason); values.put("sourceHash", sourceHash); values.put("sourceImportId", sourceImportId);
        project.setSetting(KEY, values);
    }

    private static String string(Map<?, ?> data, String key) { return data.get(key) instanceof String text ? text : ""; }
    private static <E extends Enum<E>> E enumeration(Map<?, ?> data, String key, Class<E> type, E fallback) {
        String value = string(data, key);
        return value.isBlank() ? fallback : Enum.valueOf(type, value);
    }
}
