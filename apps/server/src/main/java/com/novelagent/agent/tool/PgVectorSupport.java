package com.novelagent.agent.tool;

public final class PgVectorSupport {
    private PgVectorSupport() {
    }

    public static String literal(float[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(vector[index]);
        }
        return result.append(']').toString();
    }
}
